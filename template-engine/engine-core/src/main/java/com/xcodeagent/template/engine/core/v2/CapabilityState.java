package com.xcodeagent.template.engine.core.v2;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Canonical enabled capability state used by the V2 reconcile protocol. */
public final class CapabilityState {
    private final boolean enabled;
    private final Map<String, Object> config;

    public CapabilityState(boolean enabled, Map<String, Object> config) {
        this.enabled = enabled;
        this.config = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(config));
    }

    public boolean enabled() { return enabled; }
    public Map<String, Object> config() { return config; }

    @Override public boolean equals(Object value) {
        if (!(value instanceof CapabilityState)) return false;
        CapabilityState that = (CapabilityState) value;
        return enabled == that.enabled && config.equals(that.config);
    }
    @Override public int hashCode() { return 31 * (enabled ? 1 : 0) + config.hashCode(); }
}
