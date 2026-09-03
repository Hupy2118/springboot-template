package com.cmbchina.backend.auth.infrastructure.config;

import com.cmbchina.backend.auth.common.interceptor.ResourcePermissionInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class AuthorizationWebMvcConfiguration implements WebMvcConfigurer {
    private final ResourcePermissionInterceptor interceptor;
    public AuthorizationWebMvcConfiguration(ResourcePermissionInterceptor interceptor) { this.interceptor = interceptor; }
    @Override public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(interceptor).addPathPatterns("/**"); }
}
