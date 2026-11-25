package com.cmbchina.whitelist.adapter.web;

import com.cmb.bee.web.entity.BeeResponseEntity;
import com.cmbchina.common.exception.ServiceException;
import com.cmbchina.whitelist.adapter.web.controller.WhitelistController;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.stereotype.Component;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.util.UrlPathHelper;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class WhitelistExceptionResolver implements HandlerExceptionResolver {
    private final ObjectMapper mapper;

    @Override
    public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response,
                                         Object handler, Exception exception) {
        if (response.isCommitted() || !isWhitelistRequest(request, handler)) {
            return null;
        }

        ServiceException failure = describe(exception, response);
        try {
            byte[] body = mapper.writeValueAsBytes(BeeResponseEntity.failed(failure.code(), failure.getMessage()));
            response.setStatus(failure.status());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            response.getOutputStream().write(body);
            return new ModelAndView();
        } catch (IOException writeFailure) {
            return null;
        }
    }

    private static boolean isWhitelistRequest(HttpServletRequest request, Object handler) {
        if (handler instanceof HandlerMethod) {
            return WhitelistController.class.isAssignableFrom(((HandlerMethod) handler).getBeanType());
        }
        // Mapping failures can occur before Spring selects a controller.
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        return handler == null && ("/v1/whitelist".equals(path) || "/v1/whitelist/".equals(path)
                || "/v1/whitelist/check".equals(path) || "/v1/whitelist/check/".equals(path));
    }

    private static ServiceException describe(Exception exception, HttpServletResponse response) {
        if (exception instanceof ServiceException) {
            return (ServiceException) exception;
        }
        if (exception instanceof HttpMessageNotReadableException) {
            return new ServiceException("BAD_REQUEST", "request body must be a JSON array of strings", 400);
        }
        if (exception instanceof HttpRequestMethodNotSupportedException) {
            String[] supported = ((HttpRequestMethodNotSupportedException) exception).getSupportedMethods();
            if (supported != null) {
                response.setHeader(HttpHeaders.ALLOW, String.join(", ", supported));
            }
            return new ServiceException("BAD_REQUEST", "request method is not supported", 405);
        }
        if (exception instanceof HttpMediaTypeNotAcceptableException) {
            return new ServiceException("BAD_REQUEST", "response media type is application/json", 406);
        }
        if (exception instanceof HttpMediaTypeNotSupportedException) {
            return new ServiceException("BAD_REQUEST", "request media type must be application/json", 415);
        }
        return new ServiceException("INTERNAL_ERROR", "unexpected service failure", 500);
    }
}
