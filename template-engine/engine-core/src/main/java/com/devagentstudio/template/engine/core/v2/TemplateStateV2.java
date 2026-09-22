package com.devagentstudio.template.engine.core.v2;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** V2 state deliberately records capability facts, never a workspace content baseline. */
public final class TemplateStateV2 {
    public static final int SCHEMA_VERSION = 2;
    private final String templateRevision;
    private final Map<String, CapabilityState> requested;
    private final Map<String, CapabilityState> effective;
    private final Map<String, AppliedAdditionState> appliedAdditions;

    public TemplateStateV2(String templateRevision,
                           Map<String, CapabilityState> requested,
                           Map<String, CapabilityState> effective,
                           Map<String, AppliedAdditionState> appliedAdditions) {
        this.templateRevision = templateRevision;
        this.requested = immutable(requested);
        this.effective = immutable(effective);
        this.appliedAdditions = immutable(appliedAdditions);
    }

    private static <T> Map<String, T> immutable(Map<String, T> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<String, T>(source));
    }

    public String templateRevision() { return templateRevision; }
    public Map<String, CapabilityState> requested() { return requested; }
    public Map<String, CapabilityState> effective() { return effective; }
    public Map<String, AppliedAdditionState> appliedAdditions() { return appliedAdditions; }
}
