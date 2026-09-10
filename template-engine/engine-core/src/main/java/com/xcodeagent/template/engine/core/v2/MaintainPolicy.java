package com.xcodeagent.template.engine.core.v2;

public final class MaintainPolicy {
    public enum Mode { NO_OP, STRATEGY }
    private final Mode mode;
    private final String strategyId;
    private final int order;

    public MaintainPolicy(Mode mode, String strategyId, int order) {
        this.mode = mode;
        this.strategyId = strategyId;
        this.order = order;
    }

    public Mode mode() { return mode; }
    public String strategyId() { return strategyId; }
    public int order() { return order; }
}
