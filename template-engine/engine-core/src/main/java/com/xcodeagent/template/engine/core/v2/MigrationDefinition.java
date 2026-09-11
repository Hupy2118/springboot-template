package com.xcodeagent.template.engine.core.v2;

/** A deployable migration asset; execution remains owned by Bootstrap/XCodeAgent. */
public final class MigrationDefinition {
    private final String id, source, target, bootstrapConsumerPath, executionTrigger;
    public MigrationDefinition(String id, String source, String target, String bootstrapConsumerPath, String executionTrigger) {
        this.id = id; this.source = source; this.target = target;
        this.bootstrapConsumerPath = bootstrapConsumerPath; this.executionTrigger = executionTrigger;
    }
    public String id() { return id; } public String source() { return source; } public String target() { return target; }
    public String bootstrapConsumerPath() { return bootstrapConsumerPath; } public String executionTrigger() { return executionTrigger; }
}
