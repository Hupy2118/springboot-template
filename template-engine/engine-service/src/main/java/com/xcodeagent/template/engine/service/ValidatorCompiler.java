package com.xcodeagent.template.engine.service;

import com.xcodeagent.template.engine.core.v2.UpdateResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Compiles Registry validator templates into sorted, strict V2 wire items. */
final class ValidatorCompiler {
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> compile(UpdateResult result) {
        Map<String, Candidate> unique = new LinkedHashMap<String, Candidate>();
        for (Map<String, Object> value : result.validationPlan().validators()) {
            if (value.size() != 3 || !(value.get("validatorId") instanceof String)
                    || !(value.get("order") instanceof Number) || !(value.get("parameters") instanceof Map))
                fail("validator template");
            String id = (String) value.get("validatorId");
            if (id.trim().isEmpty()) fail("validatorId");
            Map<String, Object> parameters = new LinkedHashMap<String, Object>((Map<String, Object>) value.get("parameters"));
            Object type = parameters.remove("type");
            validate(type, parameters);
            Candidate candidate = new Candidate(id, ((Number) value.get("order")).intValue(), type, parameters);
            Candidate previous = unique.get(id);
            if (previous != null) {
                if (!previous.sameAs(candidate)) fail("validatorId conflict");
                continue;
            }
            unique.put(id, candidate);
        }
        List<Candidate> sorted = new ArrayList<Candidate>(unique.values());
        Collections.sort(sorted, new Comparator<Candidate>() {
            @Override public int compare(Candidate left, Candidate right) {
                int byOrder = Integer.compare(left.order, right.order);
                return byOrder != 0 ? byOrder : left.id.compareTo(right.id);
            }
        });
        List<Map<String, Object>> output = new ArrayList<Map<String, Object>>();
        for (int index = 0; index < sorted.size(); index++) {
            Candidate candidate = sorted.get(index);
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("validatorId", candidate.id); item.put("index", index); item.put("type", candidate.type);
            item.put("parameters", candidate.parameters); output.add(item);
        }
        return output;
    }

    private static void validate(Object rawType, Map<String, Object> parameters) {
        if (!(rawType instanceof String)) fail("validator type missing");
        String type = (String) rawType;
        requireCommon(parameters, "REAL_WORKSPACE".equals(parameters.get("executionMode")) || "SANDBOX".equals(parameters.get("executionMode")));
        if ("CAPABILITY_POSTCONDITION".equals(type)) {
            require(exact(parameters, "executionMode", "blocking", "timeoutSeconds", "workingDirectory", "capabilityId", "checks")
                    && "REAL_WORKSPACE".equals(parameters.get("executionMode")) && text(parameters, "capabilityId")
                    && checks(parameters.get("checks")), "CAPABILITY_POSTCONDITION parameters"); return;
        }
        if ("FILE_EXISTS".equals(type)) {
            require(exact(parameters, "executionMode", "blocking", "timeoutSeconds", "workingDirectory", "path")
                    && "REAL_WORKSPACE".equals(parameters.get("executionMode")) && text(parameters, "path"), "FILE_EXISTS parameters"); return;
        }
        if ("STRUCTURE_CHECK".equals(type)) {
            require(exact(parameters, "executionMode", "blocking", "timeoutSeconds", "workingDirectory", "path", "containsAll")
                    && "REAL_WORKSPACE".equals(parameters.get("executionMode")) && text(parameters, "path") && strings(parameters.get("containsAll")), "STRUCTURE_CHECK parameters"); return;
        }
        if ("JSON_STRUCTURE_CHECK".equals(type)) {
            require(exact(parameters, "executionMode", "blocking", "timeoutSeconds", "workingDirectory", "path", "pointer")
                    && "REAL_WORKSPACE".equals(parameters.get("executionMode")) && text(parameters, "path") && text(parameters, "pointer"), "JSON_STRUCTURE_CHECK parameters"); return;
        }
        if (Arrays.asList("NPM_BUILD", "NPM_TEST", "MAVEN_TEST", "MAVEN_PACKAGE").contains(type)) {
            require(exact(parameters, "executionMode", "blocking", "timeoutSeconds", "workingDirectory")
                    && "SANDBOX".equals(parameters.get("executionMode")), type + " parameters"); return;
        }
        fail("unsupported validator type");
    }

    @SuppressWarnings("unchecked") private static boolean checks(Object value) {
        if (!(value instanceof List) || ((List<?>) value).isEmpty()) return false;
        for (Object check : (List<Object>) value) {
            if (!(check instanceof Map)) return false;
            Map<String, Object> item = (Map<String, Object>) check;
            Object type = item.get("type");
            if ("FILE_EXISTS".equals(type) && exact(item, "type", "path") && text(item, "path")) continue;
            if ("STRUCTURE_CHECK".equals(type) && exact(item, "type", "path", "containsAll") && text(item, "path") && strings(item.get("containsAll"))) continue;
            if ("JSON_STRUCTURE_CHECK".equals(type) && exact(item, "type", "path", "pointer") && text(item, "path") && text(item, "pointer")) continue;
            return false;
        }
        return true;
    }

    @SuppressWarnings("unchecked") private static boolean strings(Object value) {
        if (!(value instanceof List) || ((List<?>) value).isEmpty()) return false;
        for (Object item : (List<Object>) value) if (!(item instanceof String) || ((String) item).trim().isEmpty()) return false;
        return true;
    }
    private static void requireCommon(Map<String, Object> parameters, boolean mode) {
        require(mode && parameters.get("blocking") instanceof Boolean && parameters.get("timeoutSeconds") instanceof Number
                && ((Number) parameters.get("timeoutSeconds")).intValue() > 0 && text(parameters, "workingDirectory"), "validator common parameters");
    }
    private static boolean exact(Map<String, Object> value, String... keys) { return value.size() == keys.length && allowed(value, keys); }
    private static boolean allowed(Map<String, Object> value, String... keys) { Set<String> allowed = new HashSet<String>(Arrays.asList(keys)); return allowed.containsAll(value.keySet()); }
    private static boolean text(Map<String, Object> value, String key) { return value.get(key) instanceof String && !((String) value.get(key)).trim().isEmpty(); }
    private static void require(boolean condition, String message) { if (!condition) fail(message); }
    private static void fail(String message) { throw new ServiceException("PACKAGE_BUILD_FAILED", message, 500); }

    private static final class Candidate {
        final String id; final int order; final Object type; final Map<String, Object> parameters;
        Candidate(String id, int order, Object type, Map<String, Object> parameters) { this.id = id; this.order = order; this.type = type; this.parameters = parameters; }
        boolean sameAs(Candidate other) { return order == other.order && type.equals(other.type) && parameters.equals(other.parameters); }
    }
}
