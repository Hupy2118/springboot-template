package com.devagentstudio.template.engine.service;

public final class ServiceException extends RuntimeException {
    private final String code;
    private final int status;
    /** Creates an HTTP service failure shared by service adapters. */
    public ServiceException(String code, String message, int status) {
        super(message); this.code = code; this.status = status;
    }
    String code() { return code; }
    int status() { return status; }
}
