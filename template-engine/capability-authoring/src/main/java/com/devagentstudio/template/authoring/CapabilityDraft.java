package com.devagentstudio.template.authoring;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Complete, non-Runtime intermediate representation produced from one Workbench capture. */
public final class CapabilityDraft {
    private final String capabilityId; private final List<AdditionDraft> additions; private final List<StrategyDraft> strategies; private final List<UnsupportedChange> unsupported;
    public CapabilityDraft(String capabilityId, List<AdditionDraft> additions, List<StrategyDraft> strategies, List<UnsupportedChange> unsupported) { this.capabilityId = capabilityId; this.additions = Collections.unmodifiableList(new ArrayList<AdditionDraft>(additions)); this.strategies = Collections.unmodifiableList(new ArrayList<StrategyDraft>(strategies)); this.unsupported = Collections.unmodifiableList(new ArrayList<UnsupportedChange>(unsupported)); }
    public String capabilityId() { return capabilityId; } public List<AdditionDraft> additions() { return additions; } public List<StrategyDraft> strategies() { return strategies; } public List<UnsupportedChange> unsupported() { return unsupported; }
    public boolean compilable() { return unsupported.isEmpty(); }
}
