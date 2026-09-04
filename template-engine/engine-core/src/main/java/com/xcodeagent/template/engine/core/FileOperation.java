package com.xcodeagent.template.engine.core;

/** A deterministic, workspace-relative file operation. */
public final class FileOperation {
    public enum Type { ADD_FILE, UPDATE_FILE, DELETE_FILE }
    private final Type type;
    private final String path;
    private final String content;
    public FileOperation(Type type, String path, String content) { this.type = type; this.path = path; this.content = content; }
    public Type type() { return type; }
    public String path() { return path; }
    public String content() { return content; }
}
