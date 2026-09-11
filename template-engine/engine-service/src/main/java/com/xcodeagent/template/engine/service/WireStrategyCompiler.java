package com.xcodeagent.template.engine.service;

import com.xcodeagent.template.engine.core.v2.ModificationStrategy;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Compiles one already-resolved atomic intent into the V2 wire descriptor. */
final class WireStrategyCompiler {
    Map<String, Object> compile(ModificationStrategy strategy, int index, Map<String, Object> parameters, String payloadRef) {
        require(nonEmpty(strategy.strategyId()) && safe(strategy.target()) && safePayloadRef(payloadRef), "invalid strategy identity");
        validate(strategy.type(), parameters, payloadRef);
        Map<String, Object> wire = new LinkedHashMap<String, Object>();
        wire.put("strategyId", strategy.strategyId()); wire.put("index", index); wire.put("schemaVersion", 1);
        wire.put("type", strategy.type()); wire.put("target", strategy.target()); wire.put("precondition", Collections.emptyMap());
        wire.put("parameters", parameters); wire.put("payloadRef", payloadRef); return wire;
    }

    private static void validate(String type, Map<String, Object> parameters, String payloadRef) {
        if ("ADD_FILE".equals(type)) { require(parameters.isEmpty() && nonEmpty(payloadRef), "ADD_FILE parameters"); return; }
        if ("TEXT_ANCHOR_INSERT".equals(type)) {
            require(allowed(parameters, "anchor", "position", "managedMarker", "content") && (parameters.size() == 3 || parameters.size() == 4)
                    && text(parameters, "anchor") && text(parameters, "managedMarker") && marker(parameters.get("managedMarker"))
                    && ("before".equals(parameters.get("position")) || "after".equals(parameters.get("position")))
                    && xor(parameters.get("content") instanceof String, nonEmpty(payloadRef)) && contentHasMarker(parameters.get("content"), (String) parameters.get("managedMarker")), "TEXT_ANCHOR_INSERT parameters"); return;
        }
        if ("ENSURE_IMPORT".equals(type)) { require(exact(parameters, "importStatement") && text(parameters, "importStatement") && payloadRef == null, "ENSURE_IMPORT parameters"); return; }
        if ("ENSURE_NPM_DEPENDENCY".equals(type)) { require(allowed(parameters, "name", "version", "section") && text(parameters, "name") && text(parameters, "version") && (!parameters.containsKey("section") || "dependencies".equals(parameters.get("section")) || "devDependencies".equals(parameters.get("section"))) && payloadRef == null, "ENSURE_NPM_DEPENDENCY parameters"); return; }
        if ("ENSURE_MAVEN_DEPENDENCY".equals(type)) { require(exact(parameters, "groupId", "artifactId", "version") && text(parameters, "groupId") && text(parameters, "artifactId") && text(parameters, "version") && payloadRef == null, "ENSURE_MAVEN_DEPENDENCY parameters"); return; }
        if (Arrays.asList("ENSURE_REACT_PROVIDER", "ENSURE_ROUTE", "ENSURE_MENU_ITEM", "ENSURE_SPRING_BEAN", "ENSURE_INTERCEPTOR").contains(type)) {
            require(allowed(parameters, "managedMarker", "astSelector", "content") && text(parameters, "managedMarker") && marker(parameters.get("managedMarker"))
                    && astSelector(parameters.get("astSelector")) && xor(parameters.get("content") instanceof String, nonEmpty(payloadRef))
                    && contentHasMarker(parameters.get("content"), (String) parameters.get("managedMarker")), type + " parameters"); return;
        }
        throw new ServiceException("PACKAGE_BUILD_FAILED", "unsupported Wire Strategy type", 500);
    }

    private static boolean exact(Map<String, Object> value, String... keys) { return value.size() == keys.length && allowed(value, keys); }
    private static boolean allowed(Map<String, Object> value, String... keys) { List<String> allowed = Arrays.asList(keys); for (String key : value.keySet()) if (!allowed.contains(key)) return false; return true; }
    private static boolean text(Map<String, Object> value, String key) { return value.get(key) instanceof String && nonEmpty((String) value.get(key)); }
    @SuppressWarnings("unchecked") private static boolean astSelector(Object value) {
        if (!(value instanceof Map)) return false;
        Map<String, Object> selector = (Map<String, Object>) value;
        return allowed(selector, "nodeType", "position", "name") && text(selector, "nodeType") && text(selector, "position")
                && ("before".equals(selector.get("position")) || "after".equals(selector.get("position")) || "beforeEnd".equals(selector.get("position")))
                && (!selector.containsKey("name") || text(selector, "name"));
    }
    private static boolean marker(Object value) { return value instanceof String && ((String) value).matches("[a-z0-9][a-z0-9-]*"); }
    private static boolean contentHasMarker(Object value, String marker) {
        if (value == null) return true;
        if (!(value instanceof String) || marker == null) return false;
        String content = (String) value;
        String begin = "/* xcodeagent:" + marker + ":begin */", end = "/* xcodeagent:" + marker + ":end */";
        return count(content, begin) == 1 && count(content, end) == 1 && content.indexOf(begin) < content.indexOf(end);
    }
    private static int count(String value, String needle) { int count = 0, index = 0; while ((index = value.indexOf(needle, index)) >= 0) { count++; index += needle.length(); } return count; }
    private static boolean nonEmpty(String value) { return value != null && !value.trim().isEmpty(); }
    private static boolean xor(boolean left, boolean right) { return left != right; }
    private static boolean safe(String path) { return nonEmpty(path) && !path.startsWith("/") && !path.contains("\\") && !path.contains(".."); }
    private static boolean safePayloadRef(String path) { return path == null || (path.startsWith("payload/") && safe(path)); }
    private static void require(boolean condition, String message) { if (!condition) throw new ServiceException("PACKAGE_BUILD_FAILED", message, 500); }
}
