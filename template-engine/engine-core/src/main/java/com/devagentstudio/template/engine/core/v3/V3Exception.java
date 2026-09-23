package com.devagentstudio.template.engine.core.v3;

/** A protocol failure with a stable V3 error code and HTTP status. */
public final class V3Exception extends RuntimeException {
    private final String code;
    private final int status;

    public V3Exception(String code, String message, int status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String code() { return code; }
    public int status() { return status; }
}
