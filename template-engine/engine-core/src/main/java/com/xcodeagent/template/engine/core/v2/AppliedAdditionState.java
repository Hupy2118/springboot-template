package com.xcodeagent.template.engine.core.v2;

/** Lifecycle fact for an addition; it intentionally contains no file content or digest. */
public final class AppliedAdditionState {
    private final String capabilityId;
    private final String target;
    private final String installedRevision;

    public AppliedAdditionState(String capabilityId, String target, String installedRevision) {
        this.capabilityId = capabilityId;
        this.target = target;
        this.installedRevision = installedRevision;
    }

    public String capabilityId() { return capabilityId; }
    public String target() { return target; }
    public String installedRevision() { return installedRevision; }
}
