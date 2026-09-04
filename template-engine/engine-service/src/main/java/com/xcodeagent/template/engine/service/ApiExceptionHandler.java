package com.xcodeagent.template.engine.service;

import com.xcodeagent.template.engine.source.TemplateSourceException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ServiceException.class)
    ResponseEntity<Map<String, Object>> service(ServiceException exception) { return response(exception.code(), exception.getMessage(), exception.status()); }

    @ExceptionHandler(TemplateSourceException.class)
    ResponseEntity<Map<String, Object>> core(TemplateSourceException exception) {
        String message = exception.getMessage() == null ? "template source failure" : exception.getMessage();
        String code = message.contains(":") ? message.substring(0, message.indexOf(':')) : "TEMPLATE_SOURCE_INVALID";
        return response(code, message, 400);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> malformed(HttpMessageNotReadableException exception) { return response("BAD_REQUEST", "request body must be valid JSON", 400); }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unknown(Exception exception) { return response("INTERNAL_ERROR", "unexpected service failure", 500); }

    private ResponseEntity<Map<String, Object>> response(String code, String message, int status) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("code", code); body.put("message", message); body.put("details", Collections.emptyMap()); body.put("traceId", UUID.randomUUID().toString());
        return ResponseEntity.status(status).body(body);
    }
}
