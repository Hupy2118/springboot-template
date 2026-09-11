package com.xcodeagent.template.engine.service;

import com.xcodeagent.template.engine.core.v2.AppliedAdditionState;
import com.xcodeagent.template.engine.core.v2.CapabilityState;
import com.xcodeagent.template.engine.core.v2.TemplateStateV2;
import com.xcodeagent.template.engine.core.v2.UpdateResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The one HTTP/Core mapping boundary. Core deliberately remains JSON-framework free. */
final class EngineMapper {
    private EngineMapper() { }

    static Map<String, CapabilityState> requestedV2(Object input) {
        Map<String, Object> root = object(input, "requestedConfig");
        requireOnly(root, "requestedConfig", "capabilities");
        Map<String, Object> rawCapabilities = object(root.get("capabilities"), "requestedConfig.capabilities");
        Map<String, CapabilityState> result = new LinkedHashMap<String, CapabilityState>();
        List<String> ids = new ArrayList<String>(rawCapabilities.keySet()); Collections.sort(ids);
        for (String id : ids) {
            if (id.trim().isEmpty()) bad("requestedConfig.capabilities key is empty");
            Map<String, Object> capability = object(rawCapabilities.get(id), "capability " + id);
            requireOnly(capability, "capability " + id, "enabled", "config");
            if (!(capability.get("enabled") instanceof Boolean)) bad("capability " + id + ".enabled must be boolean");
            Map<String, Object> config = object(capability.get("config"), "capability " + id + ".config");
            result.put(id, new CapabilityState(((Boolean) capability.get("enabled")).booleanValue(), config));
        }
        return result;
    }

    static TemplateStateV2 stateV2(Object input) {
        Map<String, Object> root = object(input, "currentTemplateState");
        requireOnly(root, "currentTemplateState", "schemaVersion", "templateRevision", "requested", "effective", "appliedAdditions");
        if (!Integer.valueOf(TemplateStateV2.SCHEMA_VERSION).equals(number(root.get("schemaVersion"))))
            throw new ServiceException("TEMPLATE_STATE_SCHEMA_UNSUPPORTED", "currentTemplateState.schemaVersion must be 2", 400);
        String revision = nonEmpty(root.get("templateRevision"), "currentTemplateState.templateRevision");
        return new TemplateStateV2(revision, capabilityStates(root.get("requested"), "currentTemplateState.requested"),
                capabilityStates(root.get("effective"), "currentTemplateState.effective"), additions(root.get("appliedAdditions")));
    }

    static Map<String, Object> stateV2(TemplateStateV2 state) {
        Map<String, Object> output = new LinkedHashMap<String, Object>();
        output.put("schemaVersion", TemplateStateV2.SCHEMA_VERSION);
        output.put("templateRevision", state.templateRevision());
        output.put("requested", capabilityStates(state.requested()));
        output.put("effective", capabilityStates(state.effective()));
        Map<String, Object> additions = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, AppliedAdditionState> item : state.appliedAdditions().entrySet()) {
            Map<String, Object> value = new LinkedHashMap<String, Object>();
            value.put("capabilityId", item.getValue().capabilityId()); value.put("target", item.getValue().target());
            value.put("installedRevision", item.getValue().installedRevision()); additions.put(item.getKey(), value);
        }
        output.put("appliedAdditions", additions);
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
    private static String nonEmpty(Object value, String label) { if (!(value instanceof String) || ((String) value).trim().isEmpty()) bad(label + " is required"); return (String) value; }
    private static Integer number(Object value) { return value instanceof Number ? Integer.valueOf(((Number) value).intValue()) : null; }
    private static Map<String, CapabilityState> capabilityStates(Object input, String label) {
        Map<String, Object> raw = object(input, label); Map<String, CapabilityState> result = new LinkedHashMap<String, CapabilityState>();
        List<String> ids = new ArrayList<String>(raw.keySet()); Collections.sort(ids);
        for (String id : ids) {
            Map<String, Object> state = object(raw.get(id), label + "." + id); requireOnly(state, label + "." + id, "enabled", "config");
            if (!(state.get("enabled") instanceof Boolean) || !((Boolean) state.get("enabled")).booleanValue()) bad(label + "." + id + ".enabled must be true");
            result.put(id, new CapabilityState(true, object(state.get("config"), label + "." + id + ".config")));
        }
        return result;
    }
    private static Map<String, Object> capabilityStates(Map<String, CapabilityState> input) {
        Map<String, Object> output = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, CapabilityState> item : input.entrySet()) { Map<String, Object> value = new LinkedHashMap<String, Object>(); value.put("enabled", item.getValue().enabled()); value.put("config", item.getValue().config()); output.put(item.getKey(), value); }
        return output;
    }
    private static Map<String, AppliedAdditionState> additions(Object input) {
        Map<String, Object> raw = object(input, "currentTemplateState.appliedAdditions"); Map<String, AppliedAdditionState> result = new LinkedHashMap<String, AppliedAdditionState>();
        List<String> ids = new ArrayList<String>(raw.keySet()); Collections.sort(ids);
        for (String id : ids) { Map<String, Object> value = object(raw.get(id), "currentTemplateState.appliedAdditions." + id); requireOnly(value, "currentTemplateState.appliedAdditions." + id, "capabilityId", "target", "installedRevision"); result.put(id, new AppliedAdditionState(nonEmpty(value.get("capabilityId"), "capabilityId"), nonEmpty(value.get("target"), "target"), nonEmpty(value.get("installedRevision"), "installedRevision"))); }
        return result;
    }
    private static void requireOnly(Map<String, Object> value, String label, String... keys) {
        List<String> allowed = java.util.Arrays.asList(keys);
        for (String key : value.keySet()) if (!allowed.contains(key)) bad(label + " contains unknown field " + key);
        for (String key : keys) if (!value.containsKey(key)) bad(label + " is missing " + key);
    }
    private static void bad(String message) { throw new ServiceException("BAD_REQUEST", message, 400); }
}
