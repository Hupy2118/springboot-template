package com.xcodeagent.template.authoring;

/** A deterministic, path-level difference between a Workbench baseline and project. */
public final class FileChange {
    public enum Type { ADDED, MODIFIED, DELETED }

    private final Type type;
    private final String path;

    public FileChange(Type type, String path) {
        this.type = type;
        this.path = path;
    }

    public Type type() { return type; }
    public String path() { return path; }

    @Override public boolean equals(Object value) {
        if (!(value instanceof FileChange)) return false;
        FileChange that = (FileChange) value;
        return type == that.type && path.equals(that.path);
    }

    @Override public int hashCode() { return 31 * type.hashCode() + path.hashCode(); }
    @Override public String toString() { return type + ":" + path; }
}
