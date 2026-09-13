package com.xcodeagent.template.authoring;

/** Explicit migration metadata captured from authoring.yaml; no source-file inference is allowed. */
public final class MigrationDraft {
    private final String id, source, target, bootstrapConsumerPath, executionTrigger;

    public MigrationDraft(String id, String source, String target, String bootstrapConsumerPath, String executionTrigger) {
        this.id = id; this.source = source; this.target = target;
        this.bootstrapConsumerPath = bootstrapConsumerPath; this.executionTrigger = executionTrigger;
    }
    public String id() { return id; } public String source() { return source; } public String target() { return target; }
    public String bootstrapConsumerPath() { return bootstrapConsumerPath; } public String executionTrigger() { return executionTrigger; }
}
