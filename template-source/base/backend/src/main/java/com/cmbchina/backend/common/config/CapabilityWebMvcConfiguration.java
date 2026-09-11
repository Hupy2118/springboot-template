package com.cmbchina.backend.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CapabilityWebMvcConfiguration implements WebMvcConfigurer {
    // xcodeagent:capability-interceptor-fields

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // xcodeagent:capability-interceptors
    }
}
