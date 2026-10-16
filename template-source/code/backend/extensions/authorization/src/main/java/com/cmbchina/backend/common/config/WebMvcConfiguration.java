package com.cmbchina.backend.common.config;

import com.cmbchina.backend.auth.common.interceptor.ResourcePermissionInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置，负责注册鉴权相关拦截器。
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfiguration implements WebMvcConfigurer {

    private final ResourcePermissionInterceptor resourcePermissionInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(resourcePermissionInterceptor).addPathPatterns("/api/**");
    }
}
