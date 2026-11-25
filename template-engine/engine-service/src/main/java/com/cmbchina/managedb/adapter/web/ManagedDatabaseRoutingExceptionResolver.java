package com.cmbchina.managedb.adapter.web;

import com.cmb.bee.web.entity.BeeResponseEntity;
import com.cmbchina.managedb.common.exception.BaseExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Handles mapping failures that occur before Spring selects a controller. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ManagedDatabaseRoutingExceptionResolver implements HandlerExceptionResolver {
    private final BaseExceptionHandler errors;
    private final ObjectMapper mapper;

    public ManagedDatabaseRoutingExceptionResolver(BaseExceptionHandler errors, ObjectMapper mapper) {
        this.errors = errors;
        this.mapper = mapper;
    }

    @Override
    public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response,
                                         Object handler, Exception exception) {
        if (handler != null || response.isCommitted() || !request.getServletPath().startsWith("/managed-database/")) {
            return null;
        }

        ResponseEntity<BeeResponseEntity<String>> result;
        if (exception instanceof HttpRequestMethodNotSupportedException) {
            HttpRequestMethodNotSupportedException failure = (HttpRequestMethodNotSupportedException) exception;
            result = errors.methodNotAllowed(failure);
            if (failure.getSupportedMethods() != null) {
                response.setHeader(HttpHeaders.ALLOW, String.join(", ", failure.getSupportedMethods()));
            }
        } else if (exception instanceof HttpMediaTypeNotSupportedException) {
            result = errors.unsupportedMediaType((HttpMediaTypeNotSupportedException) exception);
        } else {
            return null;
        }

        try {
            byte[] body = mapper.writeValueAsBytes(result.getBody());
            response.setStatus(result.getStatusCodeValue());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getOutputStream().write(body);
            return new ModelAndView();
        } catch (IOException writeFailure) {
            return null;
        }
    }
}
