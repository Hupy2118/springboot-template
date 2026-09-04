package com.xcodeagent.template.engine.service;

public final class ServiceException extends RuntimeException {
    private final String code;
    private final int status;
    ServiceException(String code, String message, int status) {
        super(message); this.code = code; this.status = status;
    }
    String code() { return code; }
    int status() { return status; }
}
