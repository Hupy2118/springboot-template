package com.devagentstudio.template.engine.service;

import com.devagentstudio.template.engine.core.v3.RevisionVersion;
import com.devagentstudio.template.engine.core.v3.V3Exception;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Strict boundary mapper for the V3 HTTP and TemplateState wire contracts. */
final class NextEngineMapper {
    private static final Pattern CAPABILITY_ID = Pattern.compile("^[a-z0-9][a-z0-9_-]*$");
    private static final Pattern JAVA_NAME = Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+$");
    private static final Pattern COORDINATE = Pattern.compile("^[A-Za-z0-9_.-]+$");
    private static final Set<String> STATE_FIELDS = new TreeSet<String>(Arrays.asList("schemaVersion", "templateRevision", "requested", "effective", "installedArtifacts", "managedContributions"));
    private static final Set<String> ARTIFACT_FIELDS = new TreeSet<String>(Arrays.asList("capabilityId", "target", "installedRevision"));
    private static final Set<String> CONTRIBUTION_FIELDS = new TreeSet<String>(Arrays.asList("type", "target", "owners", "spec", "appliedRevision"));

    private NextEngineMapper() { }

    @SuppressWarnings("unchecked")
    static Map<String, Object> requestedConfig(Object raw) {
        Map<String, Object> config = requireMap(raw, "BAD_REQUEST", "requestedConfig must be an object", 400);
        exact(config, Collections.singleton("capabilities"), "BAD_REQUEST", "requestedConfig fields are invalid", 400);
        Map<String, Object> capabilities = requireMap(config.get("capabilities"), "BAD_REQUEST", "requestedConfig.capabilities must be an object", 400);
        Map<String, Object> result = new TreeMap<String, Object>();
        for (Map.Entry<String, Object> entry : capabilities.entrySet()) {
            String id = entry.getKey();
            if (!CAPABILITY_ID.matcher(id).matches()) throw bad("invalid capability id: " + id);
            Map<String, Object> request = requireMap(entry.getValue(), "BAD_REQUEST", "capability request must be an object: " + id, 400);
            exact(request, new TreeSet<String>(Arrays.asList("enabled", "config")), "BAD_REQUEST", "capability request fields are invalid: " + id, 400);
            Object enabled = request.get("enabled");
            if (!(enabled instanceof Boolean)) throw bad("capability enabled must be a boolean: " + id);
            Map<String, Object> capabilityConfig = requireMap(request.get("config"), "BAD_REQUEST", "capability config must be an object: " + id, 400);
            Map<String, Object> normalized = new LinkedHashMap<String, Object>();
            normalized.put("enabled", enabled); normalized.put("config", deep(capabilityConfig));
            result.put(id, normalized);
        }
        return result;
    }

    static Map<String, Object> templateState(Object raw) {
        Map<String, Object> state = requireMap(raw, "BAD_REQUEST", "currentTemplateState must be an object", 400);
        Object schemaVersion = state.get("schemaVersion");
        if (!integer(schemaVersion)) throw bad("currentTemplateState.schemaVersion must be an integer");
        if (new java.math.BigDecimal(schemaVersion.toString()).compareTo(java.math.BigDecimal.valueOf(3L)) != 0)
            throw new V3Exception("TEMPLATE_STATE_SCHEMA_UNSUPPORTED", "currentTemplateState.schemaVersion must be 3", 400);
        exact(state, STATE_FIELDS, "TEMPLATE_STATE_INVALID", "TemplateState fields are invalid", 400);
        String revision = state.get("templateRevision") instanceof String ? (String) state.get("templateRevision") : null;
        if (revision == null) invalidState("templateRevision must be a string");
        try { RevisionVersion.parse(revision); } catch (IllegalArgumentException e) { invalidState("templateRevision is invalid"); }

        Map<String, Map<String, Object>> requested = capabilityStates(state.get("requested"), "requested");
        Map<String, Map<String, Object>> effective = capabilityStates(state.get("effective"), "effective");
        if (!effective.keySet().containsAll(requested.keySet())) invalidState("requested capabilities must be contained in effective");
        Map<String, Map<String, Object>> artifacts = artifacts(state.get("installedArtifacts"), effective);
        Map<String, Map<String, Object>> contributions = contributions(state.get("managedContributions"), effective);

        Map<String, Object> normalized = new LinkedHashMap<String, Object>();
        normalized.put("schemaVersion", 3); normalized.put("templateRevision", revision);
        normalized.put("requested", requested); normalized.put("effective", effective);
        normalized.put("installedArtifacts", artifacts); normalized.put("managedContributions", contributions);
        return normalized;
    }

    private static Map<String, Map<String, Object>> capabilityStates(Object raw, String field) {
        Map<String, Object> values = requireMap(raw, "TEMPLATE_STATE_INVALID", field + " must be an object", 400);
        Map<String, Map<String, Object>> result = new TreeMap<String, Map<String, Object>>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (!CAPABILITY_ID.matcher(entry.getKey()).matches()) invalidState(field + " has invalid capability id");
            Map<String, Object> value = requireMap(entry.getValue(), "TEMPLATE_STATE_INVALID", field + " capability state must be an object", 400);
            exact(value, new TreeSet<String>(Arrays.asList("enabled", "config")), "TEMPLATE_STATE_INVALID", field + " capability state fields are invalid", 400);
            if (!Boolean.TRUE.equals(value.get("enabled"))) invalidState(field + " may contain only enabled capabilities");
            Map<String, Object> config = requireMap(value.get("config"), "TEMPLATE_STATE_INVALID", "capability config must be an object", 400);
            Map<String, Object> canonical = new LinkedHashMap<String, Object>(); canonical.put("enabled", Boolean.TRUE); canonical.put("config", deep(config));
            result.put(entry.getKey(), canonical);
        }
        return result;
    }

    private static Map<String, Map<String, Object>> artifacts(Object raw, Map<String, Map<String, Object>> effective) {
        Map<String, Object> values = requireMap(raw, "TEMPLATE_STATE_INVALID", "installedArtifacts must be an object", 400);
        Map<String, Map<String, Object>> result = new TreeMap<String, Map<String, Object>>();
        Set<String> targets = new TreeSet<String>();
        Pattern id = Pattern.compile("^(frontend|backend):([a-z0-9][a-z0-9_-]*):(.+)$");
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            java.util.regex.Matcher match = id.matcher(entry.getKey());
            if (!match.matches()) invalidState("installed Artifact ID is invalid: " + entry.getKey());
            Map<String, Object> value = requireMap(entry.getValue(), "TEMPLATE_STATE_INVALID", "installed Artifact must be an object", 400);
            exact(value, ARTIFACT_FIELDS, "TEMPLATE_STATE_INVALID", "installed Artifact fields are invalid", 400);
            String capabilityId = string(value.get("capabilityId"), "installed Artifact capabilityId");
            String target = string(value.get("target"), "installed Artifact target");
            String installedRevision = string(value.get("installedRevision"), "installed Artifact installedRevision");
            if (!match.group(2).equals(capabilityId) || !effective.containsKey(capabilityId)) invalidState("installed Artifact owner must be effective");
            if (!safePath(match.group(3)) || !target.equals(match.group(1) + "/" + match.group(3))) invalidState("installed Artifact target does not match its ID");
            revision(installedRevision, "installedRevision");
            if (!targets.add(target)) invalidState("installed Artifact targets must be unique");
            Map<String, Object> normalized = new LinkedHashMap<String, Object>(); normalized.put("capabilityId", capabilityId);
            normalized.put("target", target); normalized.put("installedRevision", installedRevision); result.put(entry.getKey(), normalized);
        }
        return result;
    }

    private static Map<String, Map<String, Object>> contributions(Object raw, Map<String, Map<String, Object>> effective) {
        Map<String, Object> values = requireMap(raw, "TEMPLATE_STATE_INVALID", "managedContributions must be an object", 400);
        Map<String, Map<String, Object>> result = new TreeMap<String, Map<String, Object>>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            Map<String, Object> value = requireMap(entry.getValue(), "TEMPLATE_STATE_INVALID", "managed Contribution must be an object", 400);
            exact(value, CONTRIBUTION_FIELDS, "TEMPLATE_STATE_INVALID", "managed Contribution fields are invalid", 400);
            String type = string(value.get("type"), "managed Contribution type");
            String target = string(value.get("target"), "managed Contribution target");
            String appliedRevision = string(value.get("appliedRevision"), "managed Contribution appliedRevision");
            revision(appliedRevision, "appliedRevision");
            if (!(value.get("owners") instanceof List)) invalidState("managed Contribution owners must be an array");
            List<?> ownersRaw = (List<?>) value.get("owners");
            if (ownersRaw.isEmpty()) invalidState("managed Contribution owners must be non-empty");
            List<String> owners = new ArrayList<String>(); Set<String> unique = new TreeSet<String>(); String last = null;
            for (Object owner : ownersRaw) {
                String id = string(owner, "managed Contribution owner");
                if (!CAPABILITY_ID.matcher(id).matches() || !unique.add(id) || !effective.containsKey(id)) invalidState("managed Contribution owners must be unique effective capability IDs");
                if (last != null && last.compareTo(id) >= 0) invalidState("managed Contribution owners must be sorted");
                last = id; owners.add(id);
            }
            Map<String, Object> spec = requireMap(value.get("spec"), "TEMPLATE_STATE_INVALID", "managed Contribution spec must be an object", 400);
            Map<String, Object> normalizedSpec = new LinkedHashMap<String, Object>();
            if ("JAVA_ANNOTATION".equals(type)) {
                if (!"backend/src/main/java/com/cmbchina/backend/Application.java".equals(target)) invalidState("Java Annotation target is invalid");
                exact(spec, Collections.singleton("annotationClass"), "TEMPLATE_STATE_INVALID", "Java Annotation spec fields are invalid", 400);
                String annotation = string(spec.get("annotationClass"), "annotationClass");
                if (!JAVA_NAME.matcher(annotation).matches() || !entry.getKey().equals("annotation:" + annotation)) invalidState("Java Annotation Resource Key is invalid");
                normalizedSpec.put("annotationClass", annotation);
            } else if ("MAVEN_DEPENDENCY".equals(type)) {
                if (!"backend/pom.xml".equals(target)) invalidState("Maven Dependency target is invalid");
                exact(spec, new TreeSet<String>(Arrays.asList("groupId", "artifactId", "version", "scope")), "TEMPLATE_STATE_INVALID", "Maven Dependency spec fields are invalid", 400);
                String groupId = string(spec.get("groupId"), "groupId"); String artifactId = string(spec.get("artifactId"), "artifactId");
                if (!COORDINATE.matcher(groupId).matches() || !COORDINATE.matcher(artifactId).matches()
                        || !entry.getKey().equals("maven:" + groupId + ":" + artifactId)) invalidState("Maven Resource Key is invalid");
                Object version = spec.get("version"); Object scope = spec.get("scope");
                if (version != null && !(version instanceof String)) invalidState("Maven version must be string or null");
                if (scope != null && (!(scope instanceof String) || !Arrays.asList("compile", "provided", "runtime", "test", "system").contains(scope))) invalidState("Maven scope is invalid");
                normalizedSpec.put("groupId", groupId); normalizedSpec.put("artifactId", artifactId); normalizedSpec.put("version", version); normalizedSpec.put("scope", scope);
            } else invalidState("managed Contribution type is unsupported");
            Map<String, Object> normalized = new LinkedHashMap<String, Object>(); normalized.put("type", type); normalized.put("target", target);
            normalized.put("owners", owners); normalized.put("spec", normalizedSpec); normalized.put("appliedRevision", appliedRevision);
            result.put(entry.getKey(), normalized);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requireMap(Object value, String code, String message, int status) {
        if (!(value instanceof Map)) throw new V3Exception(code, message, status);
        Map<?, ?> raw = (Map<?, ?>) value;
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String)) throw new V3Exception(code, message, status);
            result.put((String) entry.getKey(), entry.getValue());
        }
        return result;
    }
    private static void exact(Map<String, Object> value, Set<String> fields, String code, String message, int status) {
        if (!value.keySet().equals(fields)) throw new V3Exception(code, message, status);
    }
    private static boolean integer(Object value) {
        return value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long || value instanceof java.math.BigInteger;
    }
    private static String string(Object value, String label) { if (!(value instanceof String)) { invalidState(label + " must be a string"); } return (String) value; }
    private static void revision(String value, String label) { try { RevisionVersion.parse(value); } catch (IllegalArgumentException e) { invalidState(label + " is invalid"); } }
    private static boolean safePath(String value) {
        if (value == null || value.isEmpty() || value.startsWith("/") || value.contains("\\")) return false;
        for (String part : value.split("/")) if (part.isEmpty() || ".".equals(part) || "..".equals(part)) return false;
        return true;
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> deep(Map<String, Object> input) {
        Map<String, Object> output = new TreeMap<String, Object>();
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map) output.put(entry.getKey(), deep((Map<String, Object>) value));
            else if (value instanceof List) output.put(entry.getKey(), deepList((List<Object>) value));
            else output.put(entry.getKey(), value);
        }
        return output;
    }
    @SuppressWarnings("unchecked")
    private static List<Object> deepList(List<Object> input) {
        List<Object> output = new ArrayList<Object>();
        for (Object value : input) output.add(value instanceof Map ? deep((Map<String, Object>) value) : value instanceof List ? deepList((List<Object>) value) : value);
        return output;
    }
    private static V3Exception bad(String message) { return new V3Exception("BAD_REQUEST", message, 400); }
    private static void invalidState(String message) { throw new V3Exception("TEMPLATE_STATE_INVALID", message, 400); }
}
