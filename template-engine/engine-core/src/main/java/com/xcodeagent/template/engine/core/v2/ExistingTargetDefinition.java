package com.xcodeagent.template.engine.core.v2;

public final class ExistingTargetDefinition {
    private final String path;
    private final String strategyId;
    private final int order;

    public ExistingTargetDefinition(String path, String strategyId, int order) {
        this.path = path;
        this.strategyId = strategyId;
        this.order = order;
    }
    public String path() { return path; }
    public String strategyId() { return strategyId; }
    public int order() { return order; }
}
