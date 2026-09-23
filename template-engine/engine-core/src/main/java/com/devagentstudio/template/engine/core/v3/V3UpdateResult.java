package com.devagentstudio.template.engine.core.v3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Planned package content; no Workspace is read or changed by the Engine. */
public final class V3UpdateResult {
    public enum Kind { NO_CHANGE, CHANGE }
    private final Kind kind;
    private final Map<String, Object> currentState;
    private final Map<String, Object> nextState;
    private final String mode;
    private final List<Map<String, Object>> operations;
    private final Map<String, byte[]> payloads;

    private V3UpdateResult(Kind kind, Map<String, Object> currentState, Map<String, Object> nextState, String mode,
                           List<Map<String, Object>> operations, Map<String, byte[]> payloads) {
        this.kind = kind; this.currentState = currentState; this.nextState = nextState; this.mode = mode;
        this.operations = Collections.unmodifiableList(new ArrayList<Map<String, Object>>(operations));
        this.payloads = Collections.unmodifiableMap(new TreeMap<String, byte[]>(payloads));
    }
    public static V3UpdateResult noChange(Map<String, Object> current) {
        return new V3UpdateResult(Kind.NO_CHANGE, current, current, "APPLY", Collections.<Map<String, Object>>emptyList(), Collections.<String, byte[]>emptyMap());
    }
    public static V3UpdateResult change(Map<String, Object> current, Map<String, Object> next, String mode,
                                        List<Map<String, Object>> operations, Map<String, byte[]> payloads) {
        return new V3UpdateResult(Kind.CHANGE, current, next, mode, operations, payloads);
    }
    public Kind kind() { return kind; }
    public Map<String, Object> currentState() { return currentState; }
    public Map<String, Object> nextState() { return nextState; }
    public String mode() { return mode; }
    public List<Map<String, Object>> operations() { return operations; }
    public Map<String, byte[]> payloads() {
        Map<String, byte[]> result = new LinkedHashMap<String, byte[]>();
        for (Map.Entry<String, byte[]> entry : payloads.entrySet()) result.put(entry.getKey(), entry.getValue().clone());
        return result;
    }
}
