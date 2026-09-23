package com.devagentstudio.template.engine.source.v3;

import com.devagentstudio.template.engine.core.v3.CodeTemplateRelease;
import com.devagentstudio.template.engine.core.v3.ExtensionRelease;
import com.devagentstudio.template.engine.core.v3.RevisionVersion;
import com.devagentstudio.template.engine.core.v3.V3Exception;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Loads only the explicitly owned V3 Runtime Source trees. */
public final class CodeTemplateReleaseLoader {
    private static final Pattern ID = Pattern.compile("^[a-z0-9][a-z0-9_-]*$");
    private static final Pattern PAGE_PATH = Pattern.compile("^[a-z0-9]+(?:_[a-z0-9]+)*$");
    private static final Pattern ROOT_ROUTE_LOCAL_NAME = Pattern.compile("^[A-Z][A-Za-z0-9]*$");
    private static final Pattern JAVA_NAME = Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+$");
    private static final List<String> FE_TYPES = Arrays.asList("providers", "rootRoutes", "pageRoutes", "initializers", "errorReporters");
    private static final Set<String> REGISTRIES = new HashSet<String>(Arrays.asList(
            "src/extensions/providers.ts", "src/extensions/rootRoutes.tsx",
            "src/extensions/systemPageRoutes.ts", "src/extensions/initializers.ts",
            "src/extensions/errorReporters.ts"));

    private final Path codeRoot;
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public CodeTemplateReleaseLoader(Path codeRoot) { this.codeRoot = codeRoot.toAbsolutePath().normalize(); }

    public CodeTemplateRelease load() {
        try {
            String revision = readUtf8(codeRoot.resolve("template-revision.txt")).trim();
            RevisionVersion.parse(revision);
            Map<String, byte[]> frontendBase = readTree(codeRoot.resolve("frontend/base"), "frontend/base");
            Map<String, byte[]> backendBase = readTree(codeRoot.resolve("backend/base"), "backend/base");
            for (String path : frontendBase.keySet()) if (REGISTRIES.contains(path)) sourceError("Base may not own generated registry " + path);

            Map<String, PartialExtension> partials = new TreeMap<String, PartialExtension>();
            loadExtensions(codeRoot.resolve("frontend/extensions"), true, partials);
            loadExtensions(codeRoot.resolve("backend/extensions"), false, partials);
            Map<String, ExtensionRelease> extensions = new TreeMap<String, ExtensionRelease>();
            for (PartialExtension partial : partials.values()) extensions.put(partial.id, partial.finish());
            validateGraph(extensions);
            validateGlobalContracts(extensions);
            validateBaseAndExtensionCollisions(frontendBase, backendBase, extensions);
            return new CodeTemplateRelease(revision, frontendBase, backendBase, extensions);
        } catch (V3Exception e) {
            throw e;
        } catch (Exception e) {
            throw new V3Exception("TEMPLATE_SOURCE_INVALID", "invalid Code Template Release: " + safeMessage(e), 500);
        }
    }

    private void loadExtensions(Path parent, boolean frontend, Map<String, PartialExtension> partials) throws IOException {
        requireDirectory(parent, frontend ? "frontend/extensions" : "backend/extensions");
        List<Path> children = new ArrayList<Path>();
        try (java.util.stream.Stream<Path> stream = Files.list(parent)) { stream.forEach(children::add); }
        Collections.sort(children, Comparator.comparing(path -> path.getFileName().toString()));
        for (Path directory : children) {
            if (Files.isSymbolicLink(directory)) sourceError("symlink is not allowed: " + relative(directory));
            if (!Files.isDirectory(directory)) {
                if (!".gitkeep".equals(directory.getFileName().toString())) sourceError("unexpected extensions entry: " + relative(directory));
                continue;
            }
            String directoryId = directory.getFileName().toString();
            if (!ID.matcher(directoryId).matches()) sourceError("invalid extension directory id: " + directoryId);
            Path manifestPath = directory.resolve("extension.yaml");
            JsonNode manifest = readDocument(manifestPath);
            String id = text(manifest, "id", true);
            if (!directoryId.equals(id) || !ID.matcher(id).matches()) sourceError("extension id must match directory: " + relative(directory));
            PartialExtension partial = partials.get(id);
            if (partial == null) { partial = new PartialExtension(id); partials.put(id, partial); }
            List<String> requires = stringArray(manifest.path("requires"), "requires");
            if (partial.requires != null && !partial.requires.equals(requires))
                throw new V3Exception("EXTENSION_CONTRACT_MISMATCH", "frontend and backend requires differ for " + id, 500);
            partial.requires = requires;

            if (frontend) loadFrontend(directory, manifest, partial);
            else loadBackend(directory, manifest, partial);
            validateExtensionRoot(directory, frontend);
        }
    }

    private void loadFrontend(Path directory, JsonNode manifest, PartialExtension partial) throws IOException {
        JsonNode contributes = manifest.path("contributes");
        if (!contributes.isObject()) sourceError(partial.id + " contributes must be an object");
        Map<String, byte[]> files = withPrefix("src/", readTree(directory.resolve("src"), relative(directory.resolve("src"))));
        partial.frontendFiles = files;
        JsonNode defaultConfig = manifest.path("defaultConfig");
        if (defaultConfig.isObject()) partial.defaultConfig = objectMap(defaultConfig);
        for (String type : FE_TYPES) {
            JsonNode entries = contributes.path(type);
            if (!entries.isArray()) sourceError(partial.id + ".contributes." + type + " must be an array");
            List<Map<String, Object>> output = new ArrayList<Map<String, Object>>();
            Set<String> ids = new HashSet<String>();
            for (JsonNode entry : entries) {
                Map<String, Object> item = objectMap(entry);
                String contributionId = scalarString(entry, "id", false);
                if (contributionId == null || contributionId.trim().isEmpty() || !ids.add(contributionId))
                    sourceError(partial.id + " has missing or duplicate " + type + " contribution id");
                if (!type.equals("pageRoutes")) validateFrontendSource(partial.id, entry, files);
                if ("rootRoutes".equals(type)) {
                    String route = scalarString(entry, "path", true);
                    if (!route.startsWith("/")) sourceError(partial.id + " root route path must start with /");
                    String localName = scalarString(entry, "localName", true);
                    if (!ROOT_ROUTE_LOCAL_NAME.matcher(localName).matches())
                        sourceError(partial.id + " root route localName is invalid: " + localName);
                }
                if ("pageRoutes".equals(type)) {
                    String route = scalarString(entry, "path", true);
                    if (!PAGE_PATH.matcher(route).matches()) sourceError(partial.id + " page route path is invalid: " + route);
                    String pageName = pascalRoute(route);
                    String pageFile = "src/pages/" + pageName + "/index.tsx";
                    if (!files.containsKey(pageFile)) sourceError(partial.id + " page route source is missing: " + pageFile);
                }
                output.add(item);
            }
            partial.frontendContributions.put(type, output);
        }
        for (String path : files.keySet()) if (REGISTRIES.contains(path)) sourceError("extension may not own generated registry " + partial.id + ": " + path);
    }

    private void validateFrontendSource(String id, JsonNode item, Map<String, byte[]> files) {
        String source = scalarString(item, "source", true);
        String export = scalarString(item, "export", true);
        if (!source.startsWith("src/") || !safeRelative(source) || !files.containsKey(source))
            sourceError(id + " contribution source is missing or unsafe: " + source);
        String content = new String(files.get(source), StandardCharsets.UTF_8);
        Pattern exportPattern = "default".equals(export)
                ? Pattern.compile("export\\s+default\\b")
                : Pattern.compile("export\\s+(?:async\\s+)?(?:const|let|var|function|class)\\s+" + Pattern.quote(export) + "\\b|export\\s*\\{[^}]*\\b" + Pattern.quote(export) + "\\b");
        if (!exportPattern.matcher(content).find()) sourceError(id + " source does not export " + export + ": " + source);
    }

    private void loadBackend(Path directory, JsonNode manifest, PartialExtension partial) throws IOException {
        JsonNode contributes = manifest.path("contributes");
        if (!contributes.isObject()) sourceError(partial.id + " backend contributes must be an object");
        Map<String, byte[]> files = new LinkedHashMap<String, byte[]>();
        for (String child : Arrays.asList("src", "docs", "migrations")) {
            Path path = directory.resolve(child);
            if (Files.exists(path) || Files.isSymbolicLink(path)) {
                if (Files.isSymbolicLink(path)) sourceError("symlink is not allowed: " + relative(path));
                merge(files, withPrefix(child + "/", readTree(path, relative(path))));
            }
        }
        partial.backendFiles = files;
        JsonNode annotations = contributes.path("applicationAnnotations");
        if (annotations.isMissingNode()) annotations = yaml.createArrayNode();
        if (!annotations.isArray()) sourceError(partial.id + ".contributes.applicationAnnotations must be an array");
        Set<String> annotationSet = new HashSet<String>();
        for (JsonNode annotation : annotations) {
            String fqcn = annotation.path("annotationClass").asText("");
            if (!JAVA_NAME.matcher(fqcn).matches() || !annotationSet.add(fqcn)) sourceError(partial.id + " has invalid or duplicate annotation contribution");
            partial.annotations.add(fqcn);
        }
        JsonNode dependencies = contributes.path("mavenDependencies");
        if (dependencies.isMissingNode()) dependencies = yaml.createArrayNode();
        if (!dependencies.isArray()) sourceError(partial.id + ".contributes.mavenDependencies must be an array");
        Set<String> coordinates = new HashSet<String>();
        for (JsonNode dependency : dependencies) {
            String group = scalarString(dependency, "groupId", true);
            String artifact = scalarString(dependency, "artifactId", true);
            String coordinate = group + ":" + artifact;
            if (!group.matches("[A-Za-z0-9_.-]+") || !artifact.matches("[A-Za-z0-9_.-]+") || !coordinates.add(coordinate))
                sourceError(partial.id + " has invalid or duplicate Maven dependency " + coordinate);
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("groupId", group); item.put("artifactId", artifact);
            String version = optionalNullableString(dependency, "version");
            String scope = optionalNullableString(dependency, "scope");
            if (scope != null && !Arrays.asList("compile", "provided", "runtime", "test", "system").contains(scope))
                sourceError(partial.id + " has invalid Maven scope " + scope);
            item.put("version", version);
            item.put("scope", scope);
            partial.dependencies.add(item);
        }
    }

    private void validateExtensionRoot(Path directory, boolean frontend) throws IOException {
        try (java.util.stream.Stream<Path> stream = Files.list(directory)) {
            for (Path child : (Iterable<Path>) stream::iterator) {
                String name = child.getFileName().toString();
                if (Files.isSymbolicLink(child)) sourceError("symlink is not allowed: " + relative(child));
                if ("extension.yaml".equals(name)) continue;
                if (frontend && "src".equals(name) || !frontend && Arrays.asList("src", "docs", "migrations").contains(name)) {
                    if (!Files.isDirectory(child)) sourceError("expected extension source directory: " + relative(child));
                    continue;
                }
                if (!frontend && "scripts".equals(name) && Files.isDirectory(child)) continue;
                sourceError("unexpected extension entry: " + relative(child));
            }
        }
    }

    private void validateGraph(Map<String, ExtensionRelease> extensions) {
        for (ExtensionRelease extension : extensions.values()) {
            Set<String> unique = new HashSet<String>();
            for (String dependency : extension.requires()) {
                if (!unique.add(dependency)) sourceError("duplicate dependency " + dependency + " in " + extension.id());
                if (!extensions.containsKey(dependency)) sourceError("unknown dependency " + dependency + " in " + extension.id());
            }
        }
        Set<String> complete = new HashSet<String>();
        Set<String> active = new LinkedHashSet<String>();
        for (String id : extensions.keySet()) visit(id, extensions, complete, active);
    }

    private void visit(String id, Map<String, ExtensionRelease> extensions, Set<String> complete, Set<String> active) {
        if (complete.contains(id)) return;
        if (!active.add(id)) sourceError("circular extension dependency: " + active + " -> " + id);
        for (String dependency : extensions.get(id).requires()) visit(dependency, extensions, complete, active);
        active.remove(id); complete.add(id);
    }

    private void validateGlobalContracts(Map<String, ExtensionRelease> extensions) {
        Map<String, String> contributionOwners = new HashMap<String, String>();
        Map<String, Map<String, Object>> mavenSpecs = new HashMap<String, Map<String, Object>>();
        for (ExtensionRelease extension : extensions.values()) {
            for (String type : FE_TYPES) {
                List<Map<String, Object>> values = "providers".equals(type) ? extension.providers()
                        : "rootRoutes".equals(type) ? extension.rootRoutes()
                        : "pageRoutes".equals(type) ? extension.pageRoutes()
                        : "initializers".equals(type) ? extension.initializers() : extension.errorReporters();
                for (Map<String, Object> item : values) {
                    String key = type + ":" + item.get("id");
                    if (contributionOwners.put(key, extension.id()) != null) sourceError("duplicate frontend contribution id " + key);
                }
            }
            for (Map<String, Object> dependency : extension.mavenDependencies()) {
                String coordinate = dependency.get("groupId") + ":" + dependency.get("artifactId");
                Map<String, Object> previous = mavenSpecs.putIfAbsent(coordinate, dependency);
                if (previous != null && (!equal(previous.get("version"), dependency.get("version")) || !equal(previous.get("scope"), dependency.get("scope"))))
                    throw new V3Exception("MAVEN_DEPENDENCY_CONFLICT", "conflicting Maven dependency spec for " + coordinate, 500);
            }
        }
    }

    private void validateBaseAndExtensionCollisions(Map<String, byte[]> feBase, Map<String, byte[]> beBase,
                                                     Map<String, ExtensionRelease> extensions) {
        Set<String> feTargets = new HashSet<String>(feBase.keySet());
        Set<String> beTargets = new HashSet<String>(beBase.keySet());
        for (ExtensionRelease extension : extensions.values()) {
            for (String path : extension.frontendFiles().keySet()) if (!feTargets.add(path)) sourceError("frontend file collision at " + path);
            for (String path : extension.backendFiles().keySet()) if (!beTargets.add(path)) sourceError("backend file collision at " + path);
        }
    }

    private Map<String, byte[]> readTree(Path directory, String label) throws IOException {
        requireDirectory(directory, label);
        Map<String, byte[]> files = new TreeMap<String, byte[]>();
        try (java.util.stream.Stream<Path> stream = Files.walk(directory)) {
            List<Path> paths = new ArrayList<Path>(); stream.forEach(paths::add);
            Collections.sort(paths, Comparator.comparing(path -> directory.relativize(path).toString()));
            for (Path path : paths) {
                if (path.equals(directory)) continue;
                if (Files.isSymbolicLink(path)) sourceError("symlink is not allowed: " + relative(path));
                if (Files.isDirectory(path)) continue;
                if (!Files.isRegularFile(path)) sourceError("unsupported Runtime Source entry: " + relative(path));
                byte[] bytes = Files.readAllBytes(path);
                if (containsNul(bytes)) sourceError("binary Runtime Source is not allowed: " + relative(path));
                decode(bytes, relative(path));
                files.put(directory.relativize(path).toString().replace('\\', '/'), bytes);
            }
        }
        return files;
    }

    private JsonNode readDocument(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path)) sourceError("missing or unsafe extension manifest: " + relative(path));
        return yaml.readTree(readUtf8(path));
    }
    private String readUtf8(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path)) sourceError("missing or unsafe Runtime Source file: " + relative(path));
        byte[] bytes = Files.readAllBytes(path);
        if (containsNul(bytes)) sourceError("binary Runtime Source is not allowed: " + relative(path));
        return decode(bytes, relative(path));
    }
    private String decode(byte[] bytes, String path) {
        try { return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString(); }
        catch (Exception e) { sourceError("Runtime Source must be UTF-8: " + path); return ""; }
    }
    private void requireDirectory(Path path, String label) {
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path)) sourceError("missing or unsafe Runtime Source directory: " + label);
    }
    private JsonNode required(JsonNode object, String field) {
        JsonNode value = object.path(field); if (value.isMissingNode() || value.isNull()) sourceError("manifest is missing " + field); return value;
    }
    private String text(JsonNode object, String field, boolean required) {
        JsonNode value = object.path(field);
        if ((value.isMissingNode() || value.isNull()) && required) sourceError("manifest is missing " + field);
        if (value.isMissingNode() || value.isNull() || !value.isTextual()) return null;
        return value.asText();
    }
    private String scalarString(JsonNode object, String field, boolean required) {
        JsonNode value = object.path(field);
        if ((value.isMissingNode() || value.isNull()) && required) sourceError("manifest is missing " + field);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isTextual()) sourceError("manifest field must be a string: " + field);
        return value.asText();
    }
    private String optionalNullableString(JsonNode object, String field) {
        JsonNode value = object.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isTextual()) sourceError("manifest field must be string or null: " + field);
        return value.asText();
    }
    private List<String> stringArray(JsonNode array, String label) {
        if (!array.isArray()) sourceError("manifest " + label + " must be an array");
        List<String> values = new ArrayList<String>();
        for (JsonNode value : array) { if (!value.isTextual()) sourceError("manifest " + label + " entries must be strings"); values.add(value.asText()); }
        return values;
    }
    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(JsonNode node) {
        if (!node.isObject()) sourceError("manifest entry must be an object");
        return yaml.convertValue(node, Map.class);
    }
    private String pascalRoute(String path) {
        StringBuilder output = new StringBuilder();
        for (String part : path.split("_")) output.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        return output.toString();
    }
    private boolean safeRelative(String path) {
        if (path == null || path.isEmpty() || path.startsWith("/") || path.contains("\\")) return false;
        for (String part : path.split("/")) if (part.isEmpty() || ".".equals(part) || "..".equals(part)) return false;
        return true;
    }
    private String relative(Path path) { return codeRoot.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/'); }
    private boolean containsNul(byte[] bytes) { for (byte value : bytes) if (value == 0) return true; return false; }
    private void merge(Map<String, byte[]> target, Map<String, byte[]> source) {
        for (Map.Entry<String, byte[]> entry : source.entrySet()) if (target.put(entry.getKey(), entry.getValue()) != null) sourceError("duplicate Runtime Source path " + entry.getKey());
    }
    private Map<String, byte[]> withPrefix(String prefix, Map<String, byte[]> files) {
        Map<String, byte[]> result = new LinkedHashMap<String, byte[]>();
        for (Map.Entry<String, byte[]> entry : files.entrySet()) result.put(prefix + entry.getKey(), entry.getValue());
        return result;
    }
    private boolean equal(Object left, Object right) { return left == null ? right == null : left.equals(right); }
    private String safeMessage(Exception exception) { return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage(); }
    private void sourceError(String message) { throw new V3Exception("TEMPLATE_SOURCE_INVALID", message, 500); }

    private static final class PartialExtension {
        private final String id;
        private List<String> requires;
        private Map<String, byte[]> frontendFiles = Collections.emptyMap();
        private Map<String, byte[]> backendFiles = Collections.emptyMap();
        private Map<String, Object> defaultConfig = Collections.emptyMap();
        private final Map<String, List<Map<String, Object>>> frontendContributions = new HashMap<String, List<Map<String, Object>>>();
        private final List<String> annotations = new ArrayList<String>();
        private final List<Map<String, Object>> dependencies = new ArrayList<Map<String, Object>>();
        private PartialExtension(String id) { this.id = id; }
        private ExtensionRelease finish() {
            List<String> deps = requires == null ? Collections.<String>emptyList() : requires;
            return new ExtensionRelease(id, deps, frontendFiles, backendFiles, defaultConfig,
                    frontendContributions.containsKey("providers") ? frontendContributions.get("providers") : Collections.<Map<String, Object>>emptyList(),
                    frontendContributions.containsKey("rootRoutes") ? frontendContributions.get("rootRoutes") : Collections.<Map<String, Object>>emptyList(),
                    frontendContributions.containsKey("pageRoutes") ? frontendContributions.get("pageRoutes") : Collections.<Map<String, Object>>emptyList(),
                    frontendContributions.containsKey("initializers") ? frontendContributions.get("initializers") : Collections.<Map<String, Object>>emptyList(),
                    frontendContributions.containsKey("errorReporters") ? frontendContributions.get("errorReporters") : Collections.<Map<String, Object>>emptyList(),
                    annotations, dependencies);
        }
    }
}
