package com.xcodeagent.template.engine.core;

import java.util.Collections;
import java.util.List;

public final class CorePlanResult {
    public enum Kind { CHANGE, NO_CHANGE }
    private final Kind kind;
    private final TemplateState nextTemplateState;
    private final List<FileOperation> operations;
    CorePlanResult(Kind kind, TemplateState nextTemplateState, List<FileOperation> operations) {
        this.kind = kind; this.nextTemplateState = nextTemplateState; this.operations = Collections.unmodifiableList(operations);
    }
    public Kind kind() { return kind; }
    public TemplateState nextTemplateState() { return nextTemplateState; }
    public List<FileOperation> operations() { return operations; }
}
