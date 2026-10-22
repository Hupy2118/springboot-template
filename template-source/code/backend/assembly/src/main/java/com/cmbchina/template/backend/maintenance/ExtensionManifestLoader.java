package com.cmbchina.template.backend.maintenance;

import org.yaml.snakeyaml.Yaml;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

public final class ExtensionManifestLoader {
    private static final Pattern FQCN = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+");
    public static final class Manifest {
        public final String id; public final List<String> requires; public final List<String> annotations; public final List<MavenDependency> dependencies; public final Map<String,Object> applicationYaml; public final Map<String,Map<String,Object>> applicationProfileYaml; public final Path root;
        Manifest(String id, List<String> requires, List<String> annotations, List<MavenDependency> dependencies, Map<String,Object> applicationYaml, Map<String,Map<String,Object>> applicationProfileYaml, Path root) { this.id=id; this.requires=requires; this.annotations=annotations; this.dependencies=dependencies; this.applicationYaml=applicationYaml; this.applicationProfileYaml=applicationProfileYaml; this.root=root; }
    }
    private final Map<String, Manifest> manifests = new LinkedHashMap<String, Manifest>();
    public ExtensionManifestLoader(Path extensions) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(extensions)) {
            for (Path root : stream) if (Files.isDirectory(root) && !"node_modules".equals(root.getFileName().toString())) load(root);
        } catch (Exception e) { throw new TemplateException("INVALID_EXTENSION_DEFINITION", e.getMessage()); }
        for (Manifest manifest : manifests.values()) for (String required : manifest.requires)
            if (!manifests.containsKey(required)) throw new TemplateException("MISSING_EXTENSION_DEPENDENCY", manifest.id + " requires " + required);
    }
    @SuppressWarnings("unchecked")
    private void load(Path root) throws Exception {
        Path file = root.resolve("extension.yaml");
        if (!Files.exists(file)) throw new TemplateException("INVALID_EXTENSION_DEFINITION", "missing " + file);
        Map<String, Object> raw;
        try (InputStream in = Files.newInputStream(file)) { raw = new Yaml().load(in); }
        String id = raw.get("id") == null ? "" : String.valueOf(raw.get("id"));
        if (id.isEmpty() || manifests.containsKey(id)) throw new TemplateException("INVALID_EXTENSION_DEFINITION", "invalid or duplicate id " + id);
        List<String> requires = strings(raw.get("requires"));
        List<String> annotations = new ArrayList<String>(); List<MavenDependency> dependencies = new ArrayList<MavenDependency>();
        Object contributes = raw.get("contributes");
        if (contributes instanceof Map) {
            Object values = ((Map<String,Object>) contributes).get("applicationAnnotations");
            if (values instanceof List) for (Object value : (List<Object>) values) {
                if (!(value instanceof Map) || !FQCN.matcher(String.valueOf(((Map<String,Object>) value).get("annotationClass"))).matches())
                    throw new TemplateException("INVALID_EXTENSION_DEFINITION", "invalid annotation in " + id);
                annotations.add(String.valueOf(((Map<String,Object>) value).get("annotationClass")));
            }
            Object mavenValues = ((Map<String,Object>) contributes).get("mavenDependencies");
            if (mavenValues instanceof List) for (Object value : (List<Object>) mavenValues) {
                if (!(value instanceof Map)) throw new TemplateException("INVALID_EXTENSION_DEFINITION", "invalid maven dependency in " + id);
                Map<String,Object> dependency = (Map<String,Object>) value;
                String groupId = string(dependency.get("groupId")); String artifactId = string(dependency.get("artifactId"));
                String version = optional(dependency.get("version")); String scope = optional(dependency.get("scope"));
                if (!groupId.matches("[A-Za-z0-9_.-]+") || !artifactId.matches("[A-Za-z0-9_.-]+") || (scope != null && !scope.matches("compile|provided|runtime|test|system")))
                    throw new TemplateException("INVALID_EXTENSION_DEFINITION", "invalid maven dependency in " + id);
                dependencies.add(new MavenDependency(groupId, artifactId, version, scope));
            }
        }
        Map<String,Object> applicationYaml = new LinkedHashMap<String,Object>();
        if (contributes instanceof Map) {
            Object yaml = ((Map<String,Object>) contributes).get("applicationYaml");
            if (yaml != null && !(yaml instanceof Map)) throw new TemplateException("INVALID_EXTENSION_DEFINITION", "applicationYaml must be an object in " + id);
            if (yaml instanceof Map) applicationYaml.putAll((Map<String,Object>) yaml);
        }
        Map<String,Map<String,Object>> profileYaml = new TreeMap<String,Map<String,Object>>();
        if (contributes instanceof Map) {
            Object profiles = ((Map<String,Object>) contributes).get("applicationProfileYaml");
            if (profiles != null && !(profiles instanceof Map)) throw new TemplateException("INVALID_EXTENSION_DEFINITION", "applicationProfileYaml must be an object in " + id);
            if (profiles instanceof Map) for (Map.Entry<String,Object> entry : ((Map<String,Object>) profiles).entrySet()) {
                if (!entry.getKey().matches("[A-Za-z0-9][A-Za-z0-9_-]*") || !(entry.getValue() instanceof Map))
                    throw new TemplateException("INVALID_EXTENSION_DEFINITION", "invalid application profile YAML in " + id);
                profileYaml.put(entry.getKey(), YamlContributionCompiler.copy((Map<String,Object>) entry.getValue()));
            }
        }
        manifests.put(id, new Manifest(id, requires, annotations, dependencies, applicationYaml, profileYaml, root));
    }
    private String string(Object value) { String result=optional(value); if(result==null || result.length()==0) throw new TemplateException("INVALID_EXTENSION_DEFINITION", "missing dependency coordinate"); return result; }
    private String optional(Object value) { return value == null ? null : String.valueOf(value).trim(); }

    /** Persists only YAML contribution fields, preserving the rest of an Extension manifest. */
    @SuppressWarnings("unchecked")
    public static void writeYamlContribution(Path root, String id, String profile, Map<String,Object> value) {
        Path file=root.resolve("extensions").resolve(id).resolve("extension.yaml");
        try (InputStream in=Files.newInputStream(file)) {
            Object loaded=new Yaml().load(in); if (!(loaded instanceof Map)) throw new TemplateException("INVALID_EXTENSION_DEFINITION", "invalid " + file);
            Map<String,Object> manifest=(Map<String,Object>)loaded; Object raw=manifest.get("contributes");
            Map<String,Object> contributes=raw instanceof Map ? (Map<String,Object>)raw : new LinkedHashMap<String,Object>(); manifest.put("contributes", contributes);
            if (profile == null) contributes.put("applicationYaml", YamlContributionCompiler.copy(value));
            else {
                Object profilesRaw=contributes.get("applicationProfileYaml"); Map<String,Object> profiles=profilesRaw instanceof Map ? (Map<String,Object>)profilesRaw : new LinkedHashMap<String,Object>();
                profiles.put(profile, YamlContributionCompiler.copy(value)); contributes.put("applicationProfileYaml", profiles);
            }
            org.yaml.snakeyaml.DumperOptions options=new org.yaml.snakeyaml.DumperOptions(); options.setDefaultFlowStyle(org.yaml.snakeyaml.DumperOptions.FlowStyle.BLOCK); options.setPrettyFlow(false);
            Files.write(file, new Yaml(options).dump(manifest).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (TemplateException e) { throw e; }
        catch (Exception e) { throw new TemplateException("WORKSPACE_SYNC_FAILED", e.getMessage()); }
    }
    @SuppressWarnings("unchecked") private List<String> strings(Object raw) { List<String> result=new ArrayList<String>(); if(raw instanceof List) for(Object x:(List<Object>)raw) result.add(String.valueOf(x)); return result; }
    public List<Manifest> resolve(List<String> requested) {
        LinkedHashSet<String> closure = new LinkedHashSet<String>(); for(String id: requested) visit(id, closure, new HashSet<String>());
        List<Manifest> resolved = new ArrayList<Manifest>();
        for (String id : closure) resolved.add(manifests.get(id));
        Map<String, MavenDependency> byCoordinate = new HashMap<String, MavenDependency>();
        for (Manifest manifest : resolved) for (MavenDependency dependency : manifest.dependencies) {
            MavenDependency existing = byCoordinate.put(dependency.coordinate(), dependency);
            if (existing != null && !existing.equals(dependency)) throw new TemplateException("MAVEN_DEPENDENCY_CONFLICT", dependency.coordinate());
        }
        return resolved;
    }
    private void visit(String id, LinkedHashSet<String> closure, Set<String> active) {
        Manifest manifest=manifests.get(id); if(manifest==null) throw new TemplateException("MISSING_EXTENSION_DEPENDENCY", id);
        if(closure.contains(id)) return; if(!active.add(id)) throw new TemplateException("CIRCULAR_EXTENSION_DEPENDENCY", id);
        for(String required:manifest.requires) visit(required, closure, active); active.remove(id); closure.add(id);
    }
}
