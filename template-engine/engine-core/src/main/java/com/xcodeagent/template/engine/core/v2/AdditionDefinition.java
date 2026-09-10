package com.xcodeagent.template.engine.core.v2;

public final class AdditionDefinition {
    private final String id;
    private final String source;
    private final String target;
    private final MaintainPolicy maintainPolicy;

    public AdditionDefinition(String id, String source, String target, MaintainPolicy maintainPolicy) {
        this.id = id;
        this.source = source;
        this.target = target;
        this.maintainPolicy = maintainPolicy;
    }

    public String id() { return id; }
    public String source() { return source; }
    public String target() { return target; }
    public MaintainPolicy maintainPolicy() { return maintainPolicy; }
}
