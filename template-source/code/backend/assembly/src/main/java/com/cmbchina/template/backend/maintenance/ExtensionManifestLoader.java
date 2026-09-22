package com.cmbchina.template.backend.maintenance;

import org.yaml.snakeyaml.Yaml;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

public final class ExtensionManifestLoader {
    private static final Pattern FQCN = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+");
    public static final class Manifest {
        public final String id; public final List<String> requires; public final List<String> annotations; public final List<MavenDependency> dependencies; public final Path root;
        Manifest(String id, List<String> requires, List<String> annotations, List<MavenDependency> dependencies, Path root) { this.id=id; this.requires=requires; this.annotations=annotations; this.dependencies=dependencies; this.root=root; }
    }
    private final Map<String, Manifest> manifests = new LinkedHashMap<String, Manifest>();
    public ExtensionManifestLoader(Path extensions) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(extensions)) {
            for (Path root : stream) if (Files.isDirectory(root)) load(root);
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
        manifests.put(id, new Manifest(id, requires, annotations, dependencies, root));
    }
    private String string(Object value) { String result=optional(value); if(result==null || result.length()==0) throw new TemplateException("INVALID_EXTENSION_DEFINITION", "missing dependency coordinate"); return result; }
    private String optional(Object value) { return value == null ? null : String.valueOf(value).trim(); }
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
