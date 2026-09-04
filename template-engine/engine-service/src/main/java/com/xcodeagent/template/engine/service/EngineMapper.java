package com.xcodeagent.template.engine.service;

import com.xcodeagent.template.engine.core.CorePlanResult;
import com.xcodeagent.template.engine.core.FileOperation;
import com.xcodeagent.template.engine.core.RequestedConfig;
import com.xcodeagent.template.engine.core.TemplateState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The one HTTP/Core mapping boundary. Core deliberately remains JSON-framework free. */
final class EngineMapper {
    private EngineMapper() { }

    static RequestedConfig requested(Object input) {
        Map<String, Object> root = object(input, "requestedConfig");
        requireOnly(root, "requestedConfig", "capabilities");
        Map<String, Object> rawCapabilities = object(root.get("capabilities"), "requestedConfig.capabilities");
        Map<String, Map<String, Object>> capabilities = new LinkedHashMap<String, Map<String, Object>>();
        List<String> ids = new ArrayList<String>(rawCapabilities.keySet()); Collections.sort(ids);
        for (String id : ids) {
            if (id.trim().isEmpty()) bad("requestedConfig.capabilities key is empty");
            Map<String, Object> capability = object(rawCapabilities.get(id), "capability " + id);
            requireOnly(capability, "capability " + id, "enabled", "config");
            if (!(capability.get("enabled") instanceof Boolean)) bad("capability " + id + ".enabled must be boolean");
            Object config = capability.get("config");
            if (config != null && !(config instanceof Map)) bad("capability " + id + ".config must be object");
            Map<String, Object> normalized = new LinkedHashMap<String, Object>();
            normalized.put("enabled", capability.get("enabled"));
            if (config != null) normalized.put("config", object(config, "capability " + id + ".config"));
            capabilities.put(id, normalized);
        }
        return new RequestedConfig(capabilities);
    }

    static TemplateState state(Object input) {
        Map<String, Object> root = object(input, "currentTemplateState");
        requireOnly(root, "currentTemplateState", "templateRevision", "managedFiles", "requested", "effective");
        Object revision = root.get("templateRevision");
        if (!(revision instanceof String) || ((String) revision).trim().isEmpty()) bad("currentTemplateState.templateRevision is required");
        Map<String, String> files = new LinkedHashMap<String, String>();
        for (Map.Entry<String, Object> item : object(root.get("managedFiles"), "currentTemplateState.managedFiles").entrySet()) {
            if (!(item.getValue() instanceof String)) bad("currentTemplateState.managedFiles values must be strings");
            files.put(item.getKey(), (String) item.getValue());
        }
        return new TemplateState((String) revision, files,
                copyObject(object(root.get("requested"), "currentTemplateState.requested")),
                copyObject(object(root.get("effective"), "currentTemplateState.effective")));
    }

    static Map<String, Object> plan(CorePlanResult result) {
        Map<String, Object> output = new LinkedHashMap<String, Object>();
        output.put("kind", result.kind().name());
        output.put("nextTemplateState", state(result.nextTemplateState()));
        output.put("body", result.kind() == CorePlanResult.Kind.NO_CHANGE ? null : changeSet(result));
        output.put("diagnostics", Collections.emptyList());
        return output;
    }

    static Map<String, Object> state(TemplateState state) {
        Map<String, Object> output = new LinkedHashMap<String, Object>();
        output.put("templateRevision", state.templateRevision());
        output.put("managedFiles", state.managedFiles());
        output.put("requested", state.requested());
        output.put("effective", state.effective());
        return output;
    }

    static Map<String, Object> changeSet(CorePlanResult result) {
        Map<String, Object> output = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> operations = new ArrayList<Map<String, Object>>();
        for (FileOperation operation : result.operations()) {
            Map<String, Object> value = new LinkedHashMap<String, Object>();
            value.put("type", operation.type().name()); value.put("path", operation.path());
            if (operation.content() != null) value.put("content", operation.content());
            operations.add(value);
        }
        output.put("operations", operations);
        return output;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String label) {
        if (!(value instanceof Map)) bad(label + " must be object");
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            if (!(entry.getKey() instanceof String)) bad(label + " keys must be strings");
            result.put((String) entry.getKey(), entry.getValue());
        }
        return result;
    }
    private static Map<String, Object> copyObject(Map<String, Object> value) { return new LinkedHashMap<String, Object>(value); }
    private static void requireOnly(Map<String, Object> value, String label, String... keys) {
        List<String> allowed = java.util.Arrays.asList(keys);
        for (String key : value.keySet()) if (!allowed.contains(key)) bad(label + " contains unknown field " + key);
        for (String key : keys) if (!value.containsKey(key)) bad(label + " is missing " + key);
    }
    private static void bad(String message) { throw new ServiceException("BAD_REQUEST", message, 400); }
}
