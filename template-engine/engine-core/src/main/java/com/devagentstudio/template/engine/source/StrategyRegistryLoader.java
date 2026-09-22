package com.devagentstudio.template.engine.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.devagentstudio.template.engine.core.v2.StrategyDefinition;
import com.devagentstudio.template.engine.core.v2.AnchorDefinition;
import com.devagentstudio.template.engine.core.v2.TargetDefinition;
import com.devagentstudio.template.engine.core.v2.ValidatorDefinition;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Loads the single published V2 registry for Runtime and offline capability authoring. */
public final class StrategyRegistryLoader {
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    @SuppressWarnings("unchecked")
    public StrategyRegistry load(Path rawRoot) {
        Path root = rawRoot.toAbsolutePath().normalize();
        Map<String, Object> raw;
        try { raw = yaml.readValue(root.resolve("strategy-registry-v2.yaml").toFile(), Map.class); }
        catch (IOException e) { throw invalid("cannot parse strategy registry", e); }
        require(raw != null && Integer.valueOf(2).equals(number(raw.get("schemaVersion"))), "CAPABILITY_V2_INVALID: strategy registry schemaVersion");

        Map<String, TargetDefinition> targets = new LinkedHashMap<String, TargetDefinition>();
        for (Object value : list(raw.get("targets"), "targets")) {
            Map<String, Object> target = object(value, "target");
            String id = text(target.get("id"), "target.id");
            String targetPath = path(target.get("path"));
            Path baseTarget = root.resolve("base").resolve(targetPath).normalize();
            require(baseTarget.startsWith(root.resolve("base")) && Files.isRegularFile(baseTarget), "BASE_SURFACE_TARGET_MISSING: " + targetPath);
            require(targets.put(id, new TargetDefinition(id, targetPath)) == null, "CAPABILITY_V2_INVALID: duplicate target");
        }

        Map<String, StrategyDefinition> strategies = new LinkedHashMap<String, StrategyDefinition>();
        for (Object value : list(raw.get("strategies"), "strategies")) {
            Map<String, Object> item = object(value, "strategy");
            String id = text(item.get("id"), "strategy.id");
            TargetDefinition target = targets.get(text(item.get("targetId"), "strategy.targetId"));
            require(target != null, "CAPABILITY_V2_INVALID: unknown strategy target");
            String type = text(item.get("type"), "strategy.type");
            require(isWireStrategyType(type), "CAPABILITY_V2_INVALID: strategy.type");
            Map<String, Object> parameters = object(item.get("parameters"), "strategy.parameters");
            validateStrategyParameters(type, parameters);
            validateSurface(root, target.path(), type, parameters);
            require(isGenerateSupported(type), "GENERATE_STRATEGY_UNSUPPORTED: " + type);
            require(strategies.put(id, new StrategyDefinition(id, target.path(), type, integer(item.get("order"), "strategy.order"), parameters)) == null,
                    "CAPABILITY_V2_INVALID: duplicate strategyId");
        }

        Map<String, AnchorDefinition> anchors = new LinkedHashMap<String, AnchorDefinition>();
        for (Object value : list(raw.get("anchors"), "anchors")) {
            Map<String, Object> item = object(value, "anchor");
            String targetId = text(item.get("targetId"), "anchor.targetId");
            TargetDefinition target = targets.get(targetId);
            require(target != null, "CAPABILITY_V2_INVALID: unknown anchor target");
            String anchorKey = text(item.get("anchorKey"), "anchor.anchorKey");
            require(anchorKey.matches("[a-z0-9][a-z0-9-]*"), "CAPABILITY_V2_INVALID: anchor.anchorKey");
            String anchor = text(item.get("anchor"), "anchor.anchor");
            String position = text(item.get("position"), "anchor.position");
            require("before".equals(position) || "after".equals(position), "CAPABILITY_V2_INVALID: anchor.position");
            require(count(readBase(root, target.path()), anchor) == 1, "ANCHOR_NOT_UNIQUE: " + anchor);
            require(anchors.put(targetId + ":" + anchorKey, new AnchorDefinition(targetId, anchorKey, anchor, position)) == null,
                    "CAPABILITY_V2_INVALID: duplicate anchorKey");
        }
        for (StrategyDefinition strategy : strategies.values()) if ("TEXT_ANCHOR_INSERT".equals(strategy.type())) {
            String targetId = targetIdForPath(targets, strategy.target());
            String anchor = (String) strategy.parameters().get("anchor");
            String position = (String) strategy.parameters().get("position");
            boolean registered = false;
            for (AnchorDefinition definition : anchors.values()) if (definition.targetId().equals(targetId) && definition.anchor().equals(anchor) && definition.position().equals(position)) { registered = true; break; }
            require(registered, "CAPABILITY_V2_INVALID: TEXT_ANCHOR_INSERT must use registered anchor");
        }

        Map<String, ValidatorDefinition> validators = new LinkedHashMap<String, ValidatorDefinition>();
        for (Object value : list(raw.get("validators"), "validators")) {
            Map<String, Object> item = object(value, "validator");
            String id = text(item.get("id"), "validator.id");
            Map<String, Object> parameters = object(item.get("parameters"), "validator.parameters");
            validateValidatorParameters(parameters);
            require(validators.put(id, new ValidatorDefinition(id, integer(item.get("order"), "validator.order"), parameters)) == null,
                    "CAPABILITY_V2_INVALID: duplicate validatorId");
        }
        return new StrategyRegistry(targets, strategies, validators, anchors);
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
        if ("ENSURE_IMPORT".equals(type)) { require(importSurfaceCount(readBase(root, target)) == 1, "IMPORT_SURFACE_INVALID: " + target); return; }
        if (isStructuralStrategy(type)) { require(astSelectorMatches(readBase(root, target), object(parameters.get("astSelector"), "astSelector")) == 1, "AST_SELECTOR_NOT_UNIQUE: " + target); return; }
        if (!"TEXT_ANCHOR_INSERT".equals(type)) return;
        String source = readBase(root, target); String anchor = (String) parameters.get("anchor");
        require(count(source, anchor) == 1, "ANCHOR_NOT_UNIQUE: " + anchor);
        String marker = (String) parameters.get("managedMarker");
        require(count(source, "/* devagentstudio:" + marker + ":begin */") == 0 && count(source, "/* devagentstudio:" + marker + ":end */") == 0, "MANAGED_MARKER_ALREADY_IN_BASE: " + marker);
    }

    private static void validateValidatorParameters(Map<String, Object> parameters) {
        String type = text(parameters.get("type"), "validator.type");
        require(parameters.get("blocking") instanceof Boolean && integer(parameters.get("timeoutSeconds"), "validator.timeoutSeconds") > 0 && text(parameters.get("workingDirectory"), "validator.workingDirectory") != null, "CAPABILITY_V2_INVALID: validator common parameters");
        if ("CAPABILITY_POSTCONDITION".equals(type)) { require(exactKeys(parameters, "type", "executionMode", "blocking", "timeoutSeconds", "workingDirectory", "capabilityId", "checks") && "REAL_WORKSPACE".equals(parameters.get("executionMode")) && text(parameters.get("capabilityId"), "validator.capabilityId") != null && postconditionChecks(parameters.get("checks")), "CAPABILITY_V2_INVALID: CAPABILITY_POSTCONDITION parameters"); return; }
        if ("FILE_EXISTS".equals(type)) { require(exactKeys(parameters, "type", "executionMode", "blocking", "timeoutSeconds", "workingDirectory", "path") && "REAL_WORKSPACE".equals(parameters.get("executionMode")) && text(parameters.get("path"), "validator.path") != null, "CAPABILITY_V2_INVALID: FILE_EXISTS parameters"); return; }
        if ("STRUCTURE_CHECK".equals(type)) { require(exactKeys(parameters, "type", "executionMode", "blocking", "timeoutSeconds", "workingDirectory", "path", "containsAll") && "REAL_WORKSPACE".equals(parameters.get("executionMode")) && text(parameters.get("path"), "validator.path") != null && nonEmptyStrings(parameters.get("containsAll")), "CAPABILITY_V2_INVALID: STRUCTURE_CHECK parameters"); return; }
        if ("JSON_STRUCTURE_CHECK".equals(type)) { require(exactKeys(parameters, "type", "executionMode", "blocking", "timeoutSeconds", "workingDirectory", "path", "pointer", "expected") && "REAL_WORKSPACE".equals(parameters.get("executionMode")) && text(parameters.get("path"), "validator.path") != null && text(parameters.get("pointer"), "validator.pointer") != null && text(parameters.get("expected"), "validator.expected") != null, "CAPABILITY_V2_INVALID: JSON_STRUCTURE_CHECK parameters"); return; }
        require(("NPM_BUILD".equals(type) || "NPM_TEST".equals(type) || "MAVEN_TEST".equals(type) || "MAVEN_PACKAGE".equals(type)) && exactKeys(parameters, "type", "executionMode", "blocking", "timeoutSeconds", "workingDirectory") && "SANDBOX".equals(parameters.get("executionMode")), "CAPABILITY_V2_INVALID: validator parameters");
    }

    @SuppressWarnings("unchecked") private static boolean postconditionChecks(Object value) { if (!(value instanceof List) || ((List<?>) value).isEmpty()) return false; for (Object raw : (List<Object>) value) { if (!(raw instanceof Map)) return false; Map<String, Object> check = (Map<String, Object>) raw; Object type = check.get("type"); if ("FILE_EXISTS".equals(type) && exactKeys(check, "type", "path") && check.get("path") instanceof String) continue; if ("STRUCTURE_CHECK".equals(type) && exactKeys(check, "type", "path", "containsAll") && check.get("path") instanceof String && nonEmptyStrings(check.get("containsAll"))) continue; if ("JSON_STRUCTURE_CHECK".equals(type) && exactKeys(check, "type", "path", "pointer", "expected") && check.get("path") instanceof String && check.get("pointer") instanceof String && check.get("expected") instanceof String && !((String) check.get("expected")).trim().isEmpty()) continue; return false; } return true; }
    @SuppressWarnings("unchecked") private static boolean nonEmptyStrings(Object value) { if (!(value instanceof List) || ((List<?>) value).isEmpty()) return false; for (Object item : (List<Object>) value) if (!(item instanceof String) || ((String) item).trim().isEmpty()) return false; return true; }
    private static void validateAstSelector(Map<String, Object> selector) { require(allowedKeys(selector, "nodeType", "position", "name") && nonBlank(selector.get("nodeType")) && ("before".equals(selector.get("position")) || "after".equals(selector.get("position")) || "beforeEnd".equals(selector.get("position"))) && (!selector.containsKey("name") || nonBlank(selector.get("name"))), "CAPABILITY_V2_INVALID: astSelector"); }
    private static boolean isWireStrategyType(String type) { return Arrays.asList("ADD_FILE", "TEXT_ANCHOR_INSERT", "ENSURE_IMPORT", "ENSURE_NPM_DEPENDENCY", "ENSURE_MAVEN_DEPENDENCY", "ENSURE_REACT_PROVIDER", "ENSURE_ROUTE", "ENSURE_MENU_ITEM", "ENSURE_SPRING_BEAN", "ENSURE_INTERCEPTOR").contains(type); }
    private static boolean isGenerateSupported(String type) { return "ENSURE_IMPORT".equals(type) || "TEXT_ANCHOR_INSERT".equals(type); }
    private static boolean isStructuralStrategy(String type) { return "ENSURE_REACT_PROVIDER".equals(type) || "ENSURE_ROUTE".equals(type) || "ENSURE_MENU_ITEM".equals(type) || "ENSURE_SPRING_BEAN".equals(type) || "ENSURE_INTERCEPTOR".equals(type); }
    private static boolean nonBlank(Object value) { return value instanceof String && !((String) value).trim().isEmpty(); }
    private static boolean allowedKeys(Map<String, Object> value, String... keys) { Set<String> allowed = new HashSet<String>(); Collections.addAll(allowed, keys); return allowed.containsAll(value.keySet()); }
    private static boolean exactKeys(Map<String, Object> value, String... keys) { if (value.size() != keys.length) return false; Set<String> allowed = new HashSet<String>(); Collections.addAll(allowed, keys); return allowed.containsAll(value.keySet()); }
    private static String readBase(Path root, String target) { try { return new String(Files.readAllBytes(root.resolve("base").resolve(target)), StandardCharsets.UTF_8); } catch (IOException e) { throw invalid("cannot read base target", e); } }
    private static int importSurfaceCount(String source) { return Pattern.compile("(?m)^\\s*import\\s+(?:[\\s\\S]*?;|[^\\n]+)$").matcher(source).find() ? 1 : 0; }
    private static int astSelectorMatches(String source, Map<String, Object> selector) { String nodeType = (String) selector.get("nodeType"), name = (String) selector.get("name"), expression; if ("function_declaration".equals(nodeType)) expression = "\\bfunction\\s+" + (name == null ? "[A-Za-z_$][\\w$]*" : Pattern.quote(name)) + "\\b"; else if ("class_declaration".equals(nodeType)) expression = "\\bclass\\s+" + (name == null ? "[A-Za-z_$][\\w$]*" : Pattern.quote(name)) + "\\b"; else if ("jsx_element".equals(nodeType) && name != null) expression = "<" + Pattern.quote(name) + "(?:\\s|>|/)"; else if ("call_expression".equals(nodeType) && name != null) expression = "\\b" + Pattern.quote(name) + "\\s*\\("; else return 0; return countMatches(source, expression); }
    private static int countMatches(String source, String expression) { Matcher matcher = Pattern.compile(expression).matcher(source); int result = 0; while (matcher.find()) result++; return result; }
    private static int count(String source, String needle) { int count = 0, index = 0; while ((index = source.indexOf(needle, index)) >= 0) { count++; index += needle.length(); } return count; }
    private static String targetIdForPath(Map<String, TargetDefinition> targets, String path) { for (TargetDefinition target : targets.values()) if (target.path().equals(path)) return target.id(); return null; }
    private static TemplateSourceException invalid(String message, Exception cause) { return new TemplateSourceException("CAPABILITY_V2_INVALID: " + message + ": " + cause.getMessage()); }
    private static void require(boolean condition, String message) { if (!condition) throw new TemplateSourceException(message); }
    private static String text(Object value, String field) { require(value instanceof String && !((String) value).trim().isEmpty(), "CAPABILITY_V2_INVALID: " + field); return (String) value; }
    private static String path(Object value) { String path = text(value, "path"); require(!path.startsWith("/") && !path.contains("\\\\") && !path.contains(".."), "CAPABILITY_V2_INVALID: unsafe path"); return path; }
    private static Integer number(Object value) { return value instanceof Number ? Integer.valueOf(((Number) value).intValue()) : null; }
    private static int integer(Object value, String field) { Integer number = number(value); require(number != null, "CAPABILITY_V2_INVALID: " + field); return number.intValue(); }
    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value, String field) { require(value instanceof Map, "CAPABILITY_V2_INVALID: " + field); return new LinkedHashMap<String, Object>((Map<String, Object>) value); }
    @SuppressWarnings("unchecked") private static List<Object> list(Object value, String field) { require(value instanceof List, "CAPABILITY_V2_INVALID: " + field); return (List<Object>) value; }
}
