package com.devagentstudio.template.engine.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.devagentstudio.template.engine.core.v2.AdditionDefinition;
import com.devagentstudio.template.engine.core.v2.CapabilityDefinitionV2;
import com.devagentstudio.template.engine.core.v2.ExistingTargetDefinition;
import com.devagentstudio.template.engine.core.v2.MaintainPolicy;
import com.devagentstudio.template.engine.core.v2.MigrationDefinition;
import com.devagentstudio.template.engine.core.v2.TemplateRelease;
import com.devagentstudio.template.engine.core.v2.StrategyDefinition;
import com.devagentstudio.template.engine.core.v2.ValidatorDefinition;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Loads the V2 reconcile metadata and validates the Atomic Release contract. */
public final class CapabilityV2Loader {
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public TemplateRelease load(Path rawRoot) {
        Path root = rawRoot.toAbsolutePath().normalize();
        try {
            String revision = new String(Files.readAllBytes(root.resolve("template-revision.txt")), StandardCharsets.UTF_8).trim();
            require(!revision.isEmpty(), "TEMPLATE_REVISION_MISSING");
            return load(root, revision);
        } catch (IOException e) { throw invalid("cannot read template revision", e); }
    }

    private TemplateRelease load(Path root, String revision) {
        StrategyRegistry registry = new StrategyRegistryLoader().load(root);
        Map<String, CapabilityDefinitionV2> capabilities = new LinkedHashMap<String, CapabilityDefinitionV2>();
        Set<String> additionIds = new HashSet<String>();
        Set<String> targets = new HashSet<String>();
        List<String> ids = new ArrayList<String>();
        Path capabilitiesRoot = root.resolve("capabilities");
        try {
            java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(capabilitiesRoot);
            try {
                for (Path child : stream) if (Files.isDirectory(child)) ids.add(child.getFileName().toString());
            } finally { stream.close(); }
        } catch (IOException e) { throw invalid("cannot list V2 capabilities", e); }
        Collections.sort(ids);
        for (String id : ids) {
            Path capabilityRoot = capabilitiesRoot.resolve(id);
            Path manifest = capabilityRoot.resolve("capability-v2.yaml");
            if (!Files.exists(manifest)) throw new TemplateSourceException("CAPABILITY_V2_INVALID: missing " + manifest);
            CapabilityDefinitionV2 definition = definition(capabilityRoot, manifest, registry);
            if (!id.equals(definition.id()) || capabilities.put(definition.id(), definition) != null)
                throw new TemplateSourceException("CAPABILITY_V2_INVALID: duplicate or mismatched capability " + id);
            for (AdditionDefinition addition : definition.additions()) {
                if (!additionIds.add(addition.id())) throw new TemplateSourceException("CAPABILITY_V2_INVALID: duplicate additionId " + addition.id());
                if (!targets.add(addition.target())) throw new TemplateSourceException("CAPABILITY_V2_INVALID: duplicate addition target " + addition.target());
            }
        }
        validateDependencyGraph(capabilities);
        return new TemplateRelease(revision, capabilities, registry.strategies(), registry.validators());
    }

    @SuppressWarnings("unchecked")
    private CapabilityDefinitionV2 definition(Path root, Path manifest, StrategyRegistry registry) {
        Map<String, Object> map;
        try { map = yaml.readValue(manifest.toFile(), Map.class); }
        catch (IOException e) { throw invalid("cannot parse " + manifest, e); }
        require(map != null && Integer.valueOf(2).equals(number(map.get("schemaVersion"))), "CAPABILITY_V2_INVALID: schemaVersion");
        String id = text(map.get("id"), "id");
        List<String> requires = new ArrayList<String>();
        Object rawRequires = map.get("requires");
        if (rawRequires != null) for (Object value : list(rawRequires, "requires")) {
            if (value instanceof String) requires.add(text(value, "requires"));
            else requires.add(text(object(value, "requires").get("id"), "requires.id"));
        }
        Collections.sort(requires);
        require(new HashSet<String>(requires).size() == requires.size(), "CAPABILITY_V2_INVALID: duplicate requires");
        Map<String, Object> config = map.get("defaultConfig") == null ? Collections.<String, Object>emptyMap() : object(map.get("defaultConfig"), "defaultConfig");
        List<ExistingTargetDefinition> existing = new ArrayList<ExistingTargetDefinition>();
        for (Object value : list(map.get("existingTargets"), "existingTargets")) {
            Map<String, Object> item = object(value, "existingTargets entry");
            require(item.size() == 1, "CAPABILITY_V2_INVALID: existingTargets entry");
            StrategyDefinition strategy = registry.strategies().get(text(item.get("strategyId"), "strategyId"));
            require(strategy != null, "CAPABILITY_V2_INVALID: unknown strategyId");
            String target = strategy.target();
            existing.add(new ExistingTargetDefinition(target, strategy.id(), strategy.order()));
        }
        List<AdditionDefinition> additions = new ArrayList<AdditionDefinition>();
        Set<String> additionIds = new HashSet<String>();
        Set<String> additionTargets = new HashSet<String>();
        for (Object value : list(map.get("additions"), "additions")) {
            Map<String, Object> item = object(value, "additions entry");
            String source = path(item.get("source")); String target = path(item.get("target"));
            String additionId = text(item.get("id"), "addition id");
            require(additionIds.add(additionId), "CAPABILITY_V2_INVALID: duplicate additionId " + additionId);
            require(additionTargets.add(target), "CAPABILITY_V2_INVALID: duplicate addition target " + target);
            Path sourcePath = root.resolve(source).normalize();
            require(sourcePath.startsWith(root) && Files.isRegularFile(sourcePath), "CAPABILITY_V2_INVALID: addition source " + source);
            Map<String, Object> policy = object(item.get("maintainPolicy"), "maintainPolicy");
            String mode = text(policy.get("mode"), "maintainPolicy.mode");
            MaintainPolicy maintain;
            if ("NO_OP".equals(mode)) {
                require(!policy.containsKey("strategyId"), "CAPABILITY_V2_INVALID: NO_OP strategyId");
                maintain = new MaintainPolicy(MaintainPolicy.Mode.NO_OP, null, 0);
            } else if ("STRATEGY".equals(mode)) {
                String strategyId = text(policy.get("strategyId"), "maintainPolicy.strategyId");
                StrategyDefinition strategy = registry.strategies().get(strategyId);
                require(strategy != null && !"ADD_FILE".equals(strategy.type()) && target.equals(strategy.target()), "CAPABILITY_V2_INVALID: unknown or mismatched strategyId");
                maintain = new MaintainPolicy(MaintainPolicy.Mode.STRATEGY, strategyId, strategy.order());
            } else throw new TemplateSourceException("CAPABILITY_V2_INVALID: maintainPolicy.mode");
            additions.add(new AdditionDefinition(additionId, source, target, maintain));
        }
        List<Map<String, Object>> validators = new ArrayList<Map<String, Object>>();
        for (Object value : list(map.get("validators"), "validators")) {
            Map<String, Object> item = object(value, "validators entry"); require(item.size() == 1, "CAPABILITY_V2_INVALID: validators entry");
            ValidatorDefinition validator = registry.validators().get(text(item.get("validatorId"), "validatorId"));
            require(validator != null, "CAPABILITY_V2_INVALID: unknown validatorId");
            Map<String, Object> resolved = new LinkedHashMap<String, Object>(); resolved.put("validatorId", validator.id()); resolved.put("order", validator.order()); resolved.put("parameters", validator.parameters()); validators.add(resolved);
        }
        List<MigrationDefinition> migrations = new ArrayList<MigrationDefinition>();
        Set<String> migrationIds = new HashSet<String>();
        for (Object value : optionalList(map.get("migrations"), "migrations")) {
            Map<String, Object> item = object(value, "migration entry"); require(item.size() == 5, "MIGRATION_CONTRACT_INVALID: migration entry");
            String migrationId = text(item.get("id"), "migration.id"); String source = path(item.get("source")); String target = path(item.get("target"));
            String consumer = path(item.get("bootstrapConsumerPath")); String trigger = text(item.get("executionTrigger"), "migration.executionTrigger");
            Path sourcePath = root.resolve(source).normalize();
            require(migrationIds.add(migrationId) && sourcePath.startsWith(root) && Files.isRegularFile(sourcePath)
                    && target.equals(consumer) && hasRegisteredMigrationConsumer(root, trigger, consumer), "MIGRATION_CONTRACT_INVALID");
            migrations.add(new MigrationDefinition(migrationId, source, target, consumer, trigger));
        }
        return new CapabilityDefinitionV2(id, requires, config, existing, additions, migrations, validators);
    }

    private static void validateStrategyParameters(String type, Map<String, Object> parameters) {
        if ("ADD_FILE".equals(type)) { require(exactKeys(parameters), "CAPABILITY_V2_INVALID: ADD_FILE parameters"); return; }
        if ("ENSURE_IMPORT".equals(type)) { require(exactKeys(parameters, "importStatement") && nonBlank(parameters.get("importStatement")), "CAPABILITY_V2_INVALID: ENSURE_IMPORT parameters"); return; }
        if ("ENSURE_NPM_DEPENDENCY".equals(type)) { require(allowedKeys(parameters, "name", "version", "section") && nonBlank(parameters.get("name")) && nonBlank(parameters.get("version")) && (!parameters.containsKey("section") || "dependencies".equals(parameters.get("section")) || "devDependencies".equals(parameters.get("section"))), "CAPABILITY_V2_INVALID: ENSURE_NPM_DEPENDENCY parameters"); return; }
        if ("ENSURE_MAVEN_DEPENDENCY".equals(type)) { require(exactKeys(parameters, "groupId", "artifactId", "version") && nonBlank(parameters.get("groupId")) && nonBlank(parameters.get("artifactId")) && nonBlank(parameters.get("version")), "CAPABILITY_V2_INVALID: ENSURE_MAVEN_DEPENDENCY parameters"); return; }
        if ("TEXT_ANCHOR_INSERT".equals(type)) {
            require(allowedKeys(parameters, "anchor", "position", "managedMarker", "content") && nonBlank(parameters.get("anchor")) && nonBlank(parameters.get("managedMarker")) && nonBlank(parameters.get("content")), "CAPABILITY_V2_INVALID: TEXT_ANCHOR_INSERT parameters");
            require("before".equals(parameters.get("position")) || "after".equals(parameters.get("position")), "CAPABILITY_V2_INVALID: TEXT_ANCHOR_INSERT position");
            String marker = (String) parameters.get("managedMarker"); String content = (String) parameters.get("content");
            String begin = "/* devagentstudio:" + marker + ":begin */", end = "/* devagentstudio:" + marker + ":end */";
            require(marker.matches("[a-z0-9][a-z0-9-]*") && count(content, begin) == 1 && count(content, end) == 1 && content.indexOf(begin) < content.indexOf(end) && content.endsWith("\n"), "CAPABILITY_V2_INVALID: TEXT_ANCHOR_INSERT marker");
            return;
        }
        require(isStructuralStrategy(type), "CAPABILITY_V2_INVALID: strategy.type");
        require(allowedKeys(parameters, "managedMarker", "astSelector", "content") && nonBlank(parameters.get("managedMarker")) && nonBlank(parameters.get("content")) && parameters.get("astSelector") instanceof Map, "CAPABILITY_V2_INVALID: " + type + " parameters");
        String marker = (String) parameters.get("managedMarker");
        require(marker.matches("[a-z0-9][a-z0-9-]*") && ((String) parameters.get("content")).contains(marker), "CAPABILITY_V2_INVALID: " + type + " managedMarker");
        validateAstSelector(object(parameters.get("astSelector"), "astSelector"));
    }

    private static void validateSurface(Path root, String target, String type, Map<String, Object> parameters) {
        if ("ENSURE_IMPORT".equals(type)) {
            require(importSurfaceCount(readBase(root, target)) == 1, "IMPORT_SURFACE_INVALID: " + target);
            return;
        }
        if (isStructuralStrategy(type)) {
            require(astSelectorMatches(readBase(root, target), object(parameters.get("astSelector"), "astSelector")) == 1,
                    "AST_SELECTOR_NOT_UNIQUE: " + target);
            return;
        }
        if (!"TEXT_ANCHOR_INSERT".equals(type)) return;
        String source = readBase(root, target);
        String anchor = (String) parameters.get("anchor");
        require(count(source, anchor) == 1, "ANCHOR_NOT_UNIQUE: " + anchor);
        String marker = (String) parameters.get("managedMarker");
        require(count(source, "/* devagentstudio:" + marker + ":begin */") == 0 && count(source, "/* devagentstudio:" + marker + ":end */") == 0, "MANAGED_MARKER_ALREADY_IN_BASE: " + marker);
    }

    private static boolean isWireStrategyType(String type) { return Arrays.asList("ADD_FILE", "TEXT_ANCHOR_INSERT", "ENSURE_IMPORT", "ENSURE_NPM_DEPENDENCY", "ENSURE_MAVEN_DEPENDENCY", "ENSURE_REACT_PROVIDER", "ENSURE_ROUTE", "ENSURE_MENU_ITEM", "ENSURE_SPRING_BEAN", "ENSURE_INTERCEPTOR").contains(type); }
    /* Generate deliberately supports only the atomics materialized by V2ProjectGenerator. */
    private static boolean isGenerateSupported(String type) { return "ENSURE_IMPORT".equals(type) || "TEXT_ANCHOR_INSERT".equals(type); }
    private static boolean isStructuralStrategy(String type) { return "ENSURE_REACT_PROVIDER".equals(type) || "ENSURE_ROUTE".equals(type) || "ENSURE_MENU_ITEM".equals(type) || "ENSURE_SPRING_BEAN".equals(type) || "ENSURE_INTERCEPTOR".equals(type); }
    private static boolean hasRegisteredMigrationConsumer(Path capabilityRoot, String trigger, String consumer) {
        if (!"AUTHORIZATION_BOOTSTRAP_DDL".equals(trigger)) return false;
        Path bootstrap = capabilityRoot.resolve("backend/src/main/java/com/cmbchina/backend/auth/bootstrap/AuthorizationBootstrapCommand.java").normalize();
        if (!bootstrap.startsWith(capabilityRoot) || !Files.isRegularFile(bootstrap)) return false;
        try {
            String workspaceRelative = consumer.startsWith("backend/") ? consumer.substring("backend/".length()) : consumer;
            return new String(Files.readAllBytes(bootstrap), StandardCharsets.UTF_8).contains(workspaceRelative);
        }
        catch (IOException e) { throw invalid("cannot read migration trigger consumer", e); }
    }
    private static boolean nonBlank(Object value) { return value instanceof String && !((String) value).trim().isEmpty(); }
    private static boolean allowedKeys(Map<String, Object> value, String... keys) { Set<String> allowed = new HashSet<String>(); Collections.addAll(allowed, keys); return allowed.containsAll(value.keySet()); }

    private static void validateAstSelector(Map<String, Object> selector) {
        require(allowedKeys(selector, "nodeType", "position", "name") && nonBlank(selector.get("nodeType"))
                && ("before".equals(selector.get("position")) || "after".equals(selector.get("position")) || "beforeEnd".equals(selector.get("position")))
                && (!selector.containsKey("name") || nonBlank(selector.get("name"))), "CAPABILITY_V2_INVALID: astSelector");
    }

    private static String readBase(Path root, String target) { try { return new String(Files.readAllBytes(root.resolve("base").resolve(target)), StandardCharsets.UTF_8); } catch (IOException e) { throw invalid("cannot read base target", e); } }
    private static int importSurfaceCount(String source) { return Pattern.compile("(?m)^\\s*import\\s+(?:[\\s\\S]*?;|[^\\n]+)$").matcher(source).find() ? 1 : 0; }
    private static int astSelectorMatches(String source, Map<String, Object> selector) {
        String nodeType = (String) selector.get("nodeType"); String name = (String) selector.get("name");
        String expression;
        if ("function_declaration".equals(nodeType)) expression = "\\bfunction\\s+" + (name == null ? "[A-Za-z_$][\\w$]*" : Pattern.quote(name)) + "\\b";
        else if ("class_declaration".equals(nodeType)) expression = "\\bclass\\s+" + (name == null ? "[A-Za-z_$][\\w$]*" : Pattern.quote(name)) + "\\b";
        else if ("jsx_element".equals(nodeType) && name != null) expression = "<" + Pattern.quote(name) + "(?:\\s|>|/)";
        else if ("call_expression".equals(nodeType) && name != null) expression = "\\b" + Pattern.quote(name) + "\\s*\\(";
        else return 0; // No parser mapping means the selector is not a releaseable surface.
        return countMatches(source, expression);
    }
    private static int countMatches(String source, String expression) { Matcher matcher = Pattern.compile(expression).matcher(source); int result = 0; while (matcher.find()) result++; return result; }

    private static void validateDependencyGraph(Map<String, CapabilityDefinitionV2> capabilities) {
        for (CapabilityDefinitionV2 definition : capabilities.values()) for (String dependency : definition.requires())
            require(capabilities.containsKey(dependency) && !definition.id().equals(dependency), "CAPABILITY_DEPENDENCY_INVALID: " + definition.id());
        Set<String> visiting = new HashSet<String>(), visited = new HashSet<String>();
        for (String id : capabilities.keySet()) visitDependency(id, capabilities, visiting, visited);
    }

    private static void visitDependency(String id, Map<String, CapabilityDefinitionV2> capabilities, Set<String> visiting, Set<String> visited) {
        if (visited.contains(id)) return;
        require(visiting.add(id), "CAPABILITY_CYCLE: " + id);
        for (String dependency : capabilities.get(id).requires()) visitDependency(dependency, capabilities, visiting, visited);
        visiting.remove(id); visited.add(id);
    }

    private static void validateValidatorParameters(Map<String, Object> parameters) {
        String type = text(parameters.get("type"), "validator.type");
        require(parameters.get("blocking") instanceof Boolean && integer(parameters.get("timeoutSeconds"), "validator.timeoutSeconds") > 0
                && text(parameters.get("workingDirectory"), "validator.workingDirectory") != null, "CAPABILITY_V2_INVALID: validator common parameters");
        if ("CAPABILITY_POSTCONDITION".equals(type)) {
            require(exactKeys(parameters, "type", "executionMode", "blocking", "timeoutSeconds", "workingDirectory", "capabilityId", "checks")
                    && "REAL_WORKSPACE".equals(parameters.get("executionMode")) && text(parameters.get("capabilityId"), "validator.capabilityId") != null
                    && postconditionChecks(parameters.get("checks")), "CAPABILITY_V2_INVALID: CAPABILITY_POSTCONDITION parameters"); return;
        }
        if ("FILE_EXISTS".equals(type)) {
            require(exactKeys(parameters, "type", "executionMode", "blocking", "timeoutSeconds", "workingDirectory", "path")
                    && "REAL_WORKSPACE".equals(parameters.get("executionMode")) && text(parameters.get("path"), "validator.path") != null, "CAPABILITY_V2_INVALID: FILE_EXISTS parameters"); return;
        }
        if ("STRUCTURE_CHECK".equals(type)) {
            require(exactKeys(parameters, "type", "executionMode", "blocking", "timeoutSeconds", "workingDirectory", "path", "containsAll")
                    && "REAL_WORKSPACE".equals(parameters.get("executionMode")) && text(parameters.get("path"), "validator.path") != null
                    && nonEmptyStrings(parameters.get("containsAll")), "CAPABILITY_V2_INVALID: STRUCTURE_CHECK parameters"); return;
        }
        if ("JSON_STRUCTURE_CHECK".equals(type)) {
            require(exactKeys(parameters, "type", "executionMode", "blocking", "timeoutSeconds", "workingDirectory", "path", "pointer", "expected")
                    && "REAL_WORKSPACE".equals(parameters.get("executionMode")) && text(parameters.get("path"), "validator.path") != null
                    && text(parameters.get("pointer"), "validator.pointer") != null && text(parameters.get("expected"), "validator.expected") != null, "CAPABILITY_V2_INVALID: JSON_STRUCTURE_CHECK parameters"); return;
        }
        require(("NPM_BUILD".equals(type) || "NPM_TEST".equals(type) || "MAVEN_TEST".equals(type) || "MAVEN_PACKAGE".equals(type))
                && exactKeys(parameters, "type", "executionMode", "blocking", "timeoutSeconds", "workingDirectory")
                && "SANDBOX".equals(parameters.get("executionMode")), "CAPABILITY_V2_INVALID: validator parameters");
    }

    @SuppressWarnings("unchecked") private static boolean postconditionChecks(Object value) {
        if (!(value instanceof List) || ((List<?>) value).isEmpty()) return false;
        for (Object raw : (List<Object>) value) {
            if (!(raw instanceof Map)) return false;
            Map<String, Object> check = (Map<String, Object>) raw;
            Object type = check.get("type");
            if ("FILE_EXISTS".equals(type) && exactKeys(check, "type", "path") && check.get("path") instanceof String) continue;
            if ("STRUCTURE_CHECK".equals(type) && exactKeys(check, "type", "path", "containsAll") && check.get("path") instanceof String && nonEmptyStrings(check.get("containsAll"))) continue;
            if ("JSON_STRUCTURE_CHECK".equals(type) && exactKeys(check, "type", "path", "pointer", "expected") && check.get("path") instanceof String && check.get("pointer") instanceof String && check.get("expected") instanceof String && !((String) check.get("expected")).trim().isEmpty()) continue;
            return false;
        }
        return true;
    }

    @SuppressWarnings("unchecked") private static boolean nonEmptyStrings(Object value) {
        if (!(value instanceof List) || ((List<?>) value).isEmpty()) return false;
        for (Object item : (List<Object>) value) if (!(item instanceof String) || ((String) item).trim().isEmpty()) return false;
        return true;
    }

    private static boolean exactKeys(Map<String, Object> value, String... keys) {
        if (value.size() != keys.length) return false;
        Set<String> allowed = new HashSet<String>(); Collections.addAll(allowed, keys);
        return allowed.containsAll(value.keySet());
    }

    private static int count(String source, String needle) { int count = 0, index = 0; while ((index = source.indexOf(needle, index)) >= 0) { count++; index += needle.length(); } return count; }

    private static TemplateSourceException invalid(String message, Exception cause) { return new TemplateSourceException("CAPABILITY_V2_INVALID: " + message + ": " + cause.getMessage()); }
    private static void require(boolean condition, String message) { if (!condition) throw new TemplateSourceException(message); }
    private static String text(Object value, String field) { require(value instanceof String && !((String) value).trim().isEmpty(), "CAPABILITY_V2_INVALID: " + field); return (String) value; }
    private static String path(Object value) { String path = text(value, "path"); require(!path.startsWith("/") && !path.contains("\\") && !path.contains(".."), "CAPABILITY_V2_INVALID: unsafe path"); return path; }
    private static Integer number(Object value) { return value instanceof Number ? Integer.valueOf(((Number) value).intValue()) : null; }
    private static int integer(Object value, String field) { Integer number = number(value); require(number != null, "CAPABILITY_V2_INVALID: " + field); return number.intValue(); }
    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value, String field) { require(value instanceof Map, "CAPABILITY_V2_INVALID: " + field); return new LinkedHashMap<String, Object>((Map<String, Object>) value); }
    @SuppressWarnings("unchecked") private static List<Object> list(Object value, String field) { require(value instanceof List, "CAPABILITY_V2_INVALID: " + field); return (List<Object>) value; }
    @SuppressWarnings("unchecked") private static List<Object> optionalList(Object value, String field) { return value == null ? Collections.<Object>emptyList() : list(value, field); }
}
