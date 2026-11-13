package com.cmb.bee.web.support;

import com.cmb.bee.web.annotations.BeeResponseBody;
import com.cmb.bee.web.entity.BeeResponseEntity;
import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpEntity;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@ControllerAdvice(basePackages = "com.cmbchina.managedb")
public class MockBeeResponseAdvice implements ResponseBodyAdvice<Object> {
    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converter) {
        if (!MappingJackson2HttpMessageConverter.class.isAssignableFrom(converter)) {
            return false;
        }

        Class<?> type = returnType.getParameterType();
        if (HttpEntity.class.isAssignableFrom(type)) {
            type = ResolvableType.forMethodParameter(returnType).as(HttpEntity.class)
                    .getGeneric().resolve(Object.class);
        }
        return !ResponseBodyEmitter.class.isAssignableFrom(type)
                && !StreamingResponseBody.class.isAssignableFrom(type)
                && (returnType.hasMethodAnnotation(BeeResponseBody.class)
                || AnnotatedElementUtils.hasAnnotation(returnType.getContainingClass(), BeeResponseBody.class));
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> converter,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (!(request instanceof ServletServerHttpRequest)
                || MediaType.TEXT_EVENT_STREAM.isCompatibleWith(contentType)
                || body instanceof BeeResponseEntity) {
            return body;
        }

        ServletServerHttpRequest servletRequest = (ServletServerHttpRequest) request;
        String path = servletRequest.getServletRequest().getServletPath();
        return path.startsWith("/managed-database/") ? BeeResponseEntity.success(body) : body;
    }
}
