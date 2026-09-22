package com.cmbchina.backend.auth.infrastructure.config;

import com.cmbchina.backend.auth.common.interceptor.UserWebMvcInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class LoginWebMvcConfiguration implements WebMvcConfigurer {
    private final UserWebMvcInterceptor interceptor;

    public LoginWebMvcConfiguration(UserWebMvcInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).order(100).addPathPatterns("/**").excludePathPatterns("/api/login/mock");
    }
}
