package com.cmbchina.template.backend.maintenance;

public final class TemplateException extends RuntimeException {
    public TemplateException(String code, String message) { super(code + ": " + message); }
}
