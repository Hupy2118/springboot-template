package com.xcodeagent.template.engine.core.v2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Complete V2 service result. NO_CHANGE has no candidate state or package components. */
public final class UpdateResult {
    public enum Kind { CHANGE, NO_CHANGE }

    private final Kind kind;
    private final List<ReconcileReason> reasons;
    private final List<ModificationStrategy> strategies;
    private final TemplateStateV2 nextTemplateState;
    private final ValidationPlan validationPlan;

    private UpdateResult(Kind kind, List<ReconcileReason> reasons, List<ModificationStrategy> strategies,
                         TemplateStateV2 nextTemplateState, ValidationPlan validationPlan) {
        this.kind = kind;
        this.reasons = Collections.unmodifiableList(new ArrayList<ReconcileReason>(reasons));
        this.strategies = Collections.unmodifiableList(new ArrayList<ModificationStrategy>(strategies));
        this.nextTemplateState = nextTemplateState;
        this.validationPlan = validationPlan;
    }

    public static UpdateResult noChange() {
        return new UpdateResult(Kind.NO_CHANGE, Collections.<ReconcileReason>emptyList(),
                Collections.<ModificationStrategy>emptyList(), null, null);
    }

    public static UpdateResult change(List<ReconcileReason> reasons, List<ModificationStrategy> strategies,
                                      TemplateStateV2 nextTemplateState, ValidationPlan validationPlan) {
        return new UpdateResult(Kind.CHANGE, reasons, strategies, nextTemplateState, validationPlan);
    }

    public Kind kind() { return kind; }
    public List<ReconcileReason> reasons() { return reasons; }
    public List<ModificationStrategy> strategies() { return strategies; }
    public TemplateStateV2 nextTemplateState() { return nextTemplateState; }
    public ValidationPlan validationPlan() { return validationPlan; }
}
