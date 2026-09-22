package com.devagentstudio.template.authoring;

/** A source change the V1 compiler refuses to reinterpret as a Runtime operation. */
public final class UnsupportedChange {
    private final String path, reason;
    public UnsupportedChange(String path, String reason) { this.path = path; this.reason = reason; }
    public String path() { return path; } public String reason() { return reason; }
}
