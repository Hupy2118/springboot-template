package com.devagentstudio.template.authoring;

/** A new capability-owned file, installed once with the V1 NO_OP maintenance policy. */
public final class AdditionDraft {
    private final String id, source, target;
    public AdditionDraft(String id, String source, String target) { this.id = id; this.source = source; this.target = target; }
    public String id() { return id; } public String source() { return source; } public String target() { return target; }
}
