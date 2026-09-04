package com.xcodeagent.template.engine.source;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** Stage1 loader: validates the source contract but deliberately does not resolve capabilities. */
public final class TemplateSourceLoader {
    private static final Set<String> CANONICAL_POINTS = new HashSet<String>(Arrays.asList(
            "frontend.providers", "frontend.root-routes", "frontend.page-routes",
            "frontend.page-wrappers", "frontend.menu-hooks", "backend.spring-interceptors"));
    private static final Set<String> FORBIDDEN_SEGMENTS = new HashSet<String>(Arrays.asList(
            ".git", "node_modules", "target", "build", "dist", "coverage", ".idea", ".vscode"));
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
    private final ObjectMapper json = new ObjectMapper();

    public TemplateSourceContext load(Path rawRoot) {
        Path root = rawRoot.toAbsolutePath().normalize();
        require(Files.isDirectory(root), "TEMPLATE_SOURCE_INVALID: root missing");
        String revision = readUtf8(root.resolve("template-revision.txt")).trim();
        require(!revision.isEmpty(), "TEMPLATE_REVISION_MISSING");

        Map<String, Object> catalog = yamlMap(root.resolve("catalog.yaml"));
        require(Integer.valueOf(1).equals(number(catalog.get("schemaVersion"))), "TEMPLATE_SOURCE_INVALID: catalog schemaVersion");
        Set<String> allTargets = new HashSet<String>();
        validateBase(root, yamlMap(root.resolve("base/base.yaml")), allTargets);
        Map<String, JsonNode> extensionSchemas = validateRegistry(root, yamlMap(root.resolve("base/extension-registry.yaml")));

        Object capabilities = catalog.get("capabilities");
        require(capabilities instanceof List, "TEMPLATE_SOURCE_INVALID: catalog capabilities");
        Set<String> capabilityIds = new HashSet<String>();
        for (Object item : (List<?>) capabilities) {
            require(item instanceof Map, "TEMPLATE_SOURCE_INVALID: catalog capability entry");
            Map<String, Object> entry = castMap(item);
            String id = string(entry.get("id"), "catalog capability id");
            require(capabilityIds.add(id), "TEMPLATE_SOURCE_INVALID: duplicate capability " + id);
            String relativePath = string(entry.get("path"), "catalog capability path");
            validateCapability(root, id, safeRelative(relativePath), allTargets, extensionSchemas);
        }
        return new TemplateSourceContext(root, revision);
    }

    private void validateBase(Path root, Map<String, Object> base, Set<String> allTargets) {
        require("base".equals(base.get("id")), "TEMPLATE_SOURCE_INVALID: base id");
        validateFiles(root.resolve("base"), base, Arrays.asList("frontend", "backend"), Collections.<String>emptySet(), allTargets);
        require(base.get("validationPlan") instanceof List, "TEMPLATE_SOURCE_INVALID: validationPlan");
    }

    private Map<String, JsonNode> validateRegistry(Path root, Map<String, Object> registry) {
        Object points = registry.get("extensionPoints");
        require(points instanceof List, "TEMPLATE_SOURCE_INVALID: extensionPoints");
        Set<String> seen = new HashSet<String>();
        Map<String, JsonNode> schemas = new HashMap<String, JsonNode>();
        for (Object item : (List<?>) points) {
            Map<String, Object> point = castMap(item);
            String name = string(point.get("point"), "extension point");
            require(CANONICAL_POINTS.contains(name), "EXTENSION_POINT_INVALID: " + name);
            require(seen.add(name), "EXTENSION_POINT_INVALID: duplicate " + name);
            String schema = safeRelative(string(point.get("payloadSchema"), "payloadSchema"));
            JsonNode node = jsonNode(root.resolve("base").resolve(schema));
            require(node.isObject(), "EXTENSION_POINT_INVALID: schema " + schema);
            schemas.put(name, node);
            string(point.get("renderer"), "renderer");
            safeRelative(string(point.get("target"), "target"));
            require("MANY".equals(point.get("cardinality")), "EXTENSION_POINT_INVALID: cardinality");
            require("CONTRIBUTION_ORDER".equals(point.get("orderBy")), "EXTENSION_POINT_INVALID: orderBy");
        }
        require(seen.equals(CANONICAL_POINTS), "EXTENSION_POINT_INVALID: canonical point set");
        return schemas;
    }

    private void validateCapability(Path root, String expectedId, String relativePath, Set<String> allTargets, Map<String, JsonNode> extensionSchemas) {
        Path capabilityRoot = root.resolve(relativePath).normalize();
        require(capabilityRoot.startsWith(root) && Files.isDirectory(capabilityRoot), "TEMPLATE_SOURCE_INVALID: capability path");
        Map<String, Object> manifest = yamlMap(capabilityRoot.resolve("capability.yaml"));
        require(expectedId.equals(manifest.get("id")), "TEMPLATE_SOURCE_INVALID: capability id");
        require(Integer.valueOf(1).equals(number(manifest.get("schemaVersion"))), "TEMPLATE_SOURCE_INVALID: capability schemaVersion");
        require("config.schema.json".equals(manifest.get("configSchema")), "TEMPLATE_SOURCE_INVALID: configSchema");
        JsonNode schema = jsonNode(capabilityRoot.resolve("config.schema.json"));
        require(schema.isObject() && "object".equals(schema.path("type").asText())
                        && !schema.path("additionalProperties").asBoolean(true)
                        && schema.path("properties").isObject() && schema.path("properties").size() == 0,
                "CONFIG_INVALID: V1 capability schema must be empty object");
        validateFiles(capabilityRoot, manifest, Arrays.asList("frontend", "backend"), Collections.singleton("migrations"), allTargets);
        validateMigrations(capabilityRoot, manifest, allTargets);
        validateExtensions(manifest, extensionSchemas);
    }

    private void validateFiles(Path ownerRoot, Map<String, Object> manifest, List<String> scannedRoots, Set<String> excludedRoots, Set<String> allTargets) {
        Object rawFiles = manifest.get("files");
        require(rawFiles instanceof List, "TEMPLATE_SOURCE_INVALID: files");
        Set<String> declared = new HashSet<String>();
        Set<String> targets = new HashSet<String>();
        for (Object item : (List<?>) rawFiles) {
            Map<String, Object> file = castMap(item);
            require(file.size() == 2 && file.containsKey("source") && file.containsKey("target"), "TEMPLATE_SOURCE_INVALID: file entry");
            String source = safeRelative(string(file.get("source"), "file source"));
            String target = safeRelative(string(file.get("target"), "file target"));
            Path sourcePath = ownerRoot.resolve(source).normalize();
            require(sourcePath.startsWith(ownerRoot) && Files.isRegularFile(sourcePath, LinkOption.NOFOLLOW_LINKS), "TEMPLATE_SOURCE_INVALID: source file " + source);
            require(!Files.isSymbolicLink(sourcePath), "SYMLINK_TARGET_UNSUPPORTED: " + source);
            readUtf8(sourcePath);
            require(declared.add(source), "FILE_OWNERSHIP_CONFLICT: duplicate source " + source);
            require(targets.add(target), "FILE_OWNERSHIP_CONFLICT: duplicate target " + target);
            require(allTargets.add(target), "FILE_OWNERSHIP_CONFLICT: target " + target);
        }
        for (String rootName : scannedRoots) {
            Path scanned = ownerRoot.resolve(rootName);
            if (!Files.exists(scanned)) continue;
            try (Stream<Path> stream = Files.walk(scanned)) {
                stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).forEach(path -> {
                    String relative = ownerRoot.relativize(path).toString().replace('\\', '/');
                    if (!containsForbiddenSegment(relative) && !declared.contains(relative)) {
                        throw new TemplateSourceException("UNDECLARED_TEMPLATE_FILE: " + relative);
                    }
                });
            } catch (IOException e) {
                throw new TemplateSourceException("TEMPLATE_SOURCE_INVALID: scan " + e.getMessage());
            }
        }
    }

    private void validateMigrations(Path capabilityRoot, Map<String, Object> manifest, Set<String> allTargets) {
        Object raw = manifest.get("migrations");
        require(raw instanceof List, "TEMPLATE_SOURCE_INVALID: migrations");
        Set<String> ids = new HashSet<String>();
        Set<String> targets = new HashSet<String>();
        for (Object item : (List<?>) raw) {
            Map<String, Object> migration = castMap(item);
            require(migration.size() == 5, "TEMPLATE_SOURCE_INVALID: migration entry");
            String id = string(migration.get("id"), "migration id");
            String source = safeRelative(string(migration.get("source"), "migration source"));
            String target = safeRelative(string(migration.get("target"), "migration target"));
            require("COPY".equals(migration.get("mode")) && number(migration.get("order")) != null, "TEMPLATE_SOURCE_INVALID: migration mode/order");
            require(ids.add(id) && targets.add(target), "FILE_OWNERSHIP_CONFLICT: migration");
            require(allTargets.add(target), "FILE_OWNERSHIP_CONFLICT: migration target " + target);
            Path file = capabilityRoot.resolve(source).normalize();
            require(file.startsWith(capabilityRoot) && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS), "MIGRATION_PATH_MISSING: " + source);
            readUtf8(file);
        }
        Path migrations = capabilityRoot.resolve("migrations");
        if (Files.exists(migrations)) {
            try (Stream<Path> stream = Files.walk(migrations)) {
                stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).forEach(path -> {
                    String relative = capabilityRoot.relativize(path).toString().replace('\\', '/');
                    boolean matched = false;
                    for (Object item : (List<?>) raw) {
                        if (relative.equals(castMap(item).get("source"))) { matched = true; break; }
                    }
                    if (!matched) throw new TemplateSourceException("UNDECLARED_TEMPLATE_FILE: " + relative);
                });
            } catch (IOException e) {
                throw new TemplateSourceException("TEMPLATE_SOURCE_INVALID: migration scan " + e.getMessage());
            }
        }
    }

    private void validateExtensions(Map<String, Object> manifest, Map<String, JsonNode> extensionSchemas) {
        Object raw = manifest.get("extensions");
        require(raw instanceof List, "TEMPLATE_SOURCE_INVALID: extensions");
        Set<String> ids = new HashSet<String>();
        for (Object item : (List<?>) raw) {
            Map<String, Object> contribution = castMap(item);
            String point = string(contribution.get("point"), "extension point");
            require(CANONICAL_POINTS.contains(point), "EXTENSION_POINT_INVALID: " + point);
            String id = string(contribution.get("id"), "extension id");
            require(ids.add(point + ":" + id), "EXTENSION_CONFLICT: " + id);
            require(number(contribution.get("order")) != null, "EXTENSION_POINT_INVALID: order");
            require(contribution.get("payload") instanceof Map, "EXTENSION_POINT_INVALID: payload");
            require(matchesSchema(json.valueToTree(contribution.get("payload")), extensionSchemas.get(point)), "EXTENSION_PAYLOAD_INVALID: " + id);
        }
    }

    private boolean matchesSchema(JsonNode value, JsonNode schema) {
        if (schema == null) return false;
        JsonNode enumValues = schema.get("enum");
        if (enumValues != null) {
            boolean matched = false;
            for (JsonNode candidate : enumValues) if (candidate.equals(value)) { matched = true; break; }
            if (!matched) return false;
        }
        String type = schema.path("type").asText();
        if ("string".equals(type) && !value.isTextual()) return false;
        if ("integer".equals(type) && !value.isIntegralNumber()) return false;
        if ("array".equals(type)) {
            if (!value.isArray()) return false;
            for (JsonNode item : value) if (!matchesSchema(item, schema.path("items"))) return false;
            return true;
        }
        if (!"object".equals(type)) return true;
        if (!value.isObject()) return false;
        JsonNode properties = schema.path("properties");
        JsonNode required = schema.path("required");
        for (JsonNode key : required) if (!value.has(key.asText())) return false;
        java.util.Iterator<String> names = value.fieldNames();
        while (names.hasNext()) {
            String key = names.next();
            if (!properties.has(key) || !matchesSchema(value.get(key), properties.get(key))) return false;
        }
        return true;
    }

    private Map<String, Object> yamlMap(Path path) {
        try { return yaml.readValue(readUtf8(path), new TypeReference<Map<String, Object>>() {}); }
        catch (IOException e) { throw new TemplateSourceException("TEMPLATE_SOURCE_INVALID: " + path + " " + e.getMessage()); }
    }

    private JsonNode jsonNode(Path path) {
        try { return json.readTree(readUtf8(path)); }
        catch (IOException e) { throw new TemplateSourceException("CONFIG_INVALID: " + path + " " + e.getMessage()); }
    }

    private String readUtf8(Path path) {
        try {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
            return decoder.decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(path))).toString();
        } catch (CharacterCodingException e) { throw new TemplateSourceException("BINARY_TEMPLATE_FILE_UNSUPPORTED: " + path); }
        catch (IOException e) { throw new TemplateSourceException("TEMPLATE_SOURCE_INVALID: " + path + " " + e.getMessage()); }
    }

    private String safeRelative(String value) {
        Path path = Paths.get(value).normalize();
        require(!path.isAbsolute() && !value.isEmpty() && !value.contains("\\") && !path.startsWith("..") && !containsForbiddenSegment(value), "TEMPLATE_SOURCE_INVALID: unsafe path " + value);
        return value;
    }
    private boolean containsForbiddenSegment(String value) {
        for (String segment : value.split("/")) if (FORBIDDEN_SEGMENTS.contains(segment)) return true;
        return false;
    }
    private Integer number(Object value) { return value instanceof Number ? ((Number) value).intValue() : null; }
    private String string(Object value, String label) { require(value instanceof String && !((String) value).trim().isEmpty(), "TEMPLATE_SOURCE_INVALID: " + label); return (String) value; }
    @SuppressWarnings("unchecked") private Map<String, Object> castMap(Object value) { require(value instanceof Map, "TEMPLATE_SOURCE_INVALID: map expected"); return (Map<String, Object>) value; }
    private void require(boolean condition, String message) { if (!condition) throw new TemplateSourceException(message); }
}
