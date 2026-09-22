package com.devagentstudio.template.engine.core.v2;

/** Immutable description of a platform-managed base extension surface. */
public final class TargetDefinition {
    private final String id;
    private final String path;

    public TargetDefinition(String id, String path) {
        this.id = id;
        this.path = path;
    }

    public String id() { return id; }
    public String path() { return path; }
}
