package com.devagentstudio.template.authoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.devagentstudio.template.engine.source.TemplateSourceException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Persists only local Draft ownership; this file is intentionally outside Template Source. */
public final class CompileStateStore {
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
    @SuppressWarnings("unchecked")
    public CompileState load(Path workbench) {
        Path file = workbench.resolve("compile-state.yaml");
        if (!Files.isRegularFile(file)) return null;
        try {
            Map<String, Object> value = yaml.readValue(file.toFile(), Map.class);
            if (value == null || !(value.get("capabilityId") instanceof String)) throw new TemplateSourceException("CAPABILITY_OWNERSHIP_CONFLICT");
            return new CompileState((String) value.get("capabilityId"), strings(value.get("strategyIds")), strings(value.get("validatorIds")));
        } catch (IOException e) { throw new TemplateSourceException("CAPABILITY_OWNERSHIP_CONFLICT"); }
    }
    public void save(Path workbench, CompileState state) {
        try { yaml.writeValue(workbench.resolve("compile-state.yaml").toFile(), stateMap(state)); }
        catch (IOException e) { throw new TemplateSourceException("CAPABILITY_OWNERSHIP_CONFLICT"); }
    }
    private static Map<String, Object> stateMap(CompileState state) {
        java.util.Map<String, Object> value = new java.util.LinkedHashMap<String, Object>();
        value.put("capabilityId", state.capabilityId()); value.put("strategyIds", state.strategyIds()); value.put("validatorIds", state.validatorIds()); return value;
    }
    @SuppressWarnings("unchecked") private static List<String> strings(Object value) {
        if (value == null) return Collections.emptyList();
        if (!(value instanceof List)) throw new TemplateSourceException("CAPABILITY_OWNERSHIP_CONFLICT");
        List<String> result = new java.util.ArrayList<String>();
        for (Object item : (List<Object>) value) { if (!(item instanceof String)) throw new TemplateSourceException("CAPABILITY_OWNERSHIP_CONFLICT"); result.add((String) item); }
        return result;
    }
}
