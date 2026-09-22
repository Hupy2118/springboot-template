package com.devagentstudio.template.authoring;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Read-only explanation of how a Workbench diff is understood by the compiler. */
public final class CapabilityStatusReport {
    public enum Status { READY, BLOCKED }

    private final List<AdditionDraft> additions;
    private final List<StrategyDraft> strategies;
    private final List<UnsupportedChange> unsupportedChanges;

    public CapabilityStatusReport(CapabilityDraft draft) {
        this.additions = Collections.unmodifiableList(new ArrayList<AdditionDraft>(draft.additions()));
        this.strategies = Collections.unmodifiableList(new ArrayList<StrategyDraft>(draft.strategies()));
        this.unsupportedChanges = Collections.unmodifiableList(new ArrayList<UnsupportedChange>(draft.unsupported()));
    }

    public List<AdditionDraft> additions() { return additions; }
    public List<StrategyDraft> strategies() { return strategies; }
    public List<UnsupportedChange> unsupportedChanges() { return unsupportedChanges; }
    public Status status() { return unsupportedChanges.isEmpty() ? Status.READY : Status.BLOCKED; }
    public int importCount() { return count("ENSURE_IMPORT"); }
    public int anchorInsertCount() { return count("TEXT_ANCHOR_INSERT"); }
    private int count(String type) { int count = 0; for (StrategyDraft strategy : strategies) if (type.equals(strategy.type())) count++; return count; }
}
