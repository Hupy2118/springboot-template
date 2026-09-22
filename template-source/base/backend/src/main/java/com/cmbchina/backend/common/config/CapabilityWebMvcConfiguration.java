package com.cmbchina.backend.common.config;

import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CapabilityWebMvcConfiguration implements WebMvcConfigurer {
    private final ApplicationContext applicationContext;

    public CapabilityWebMvcConfiguration(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // devagentstudio:capability-interceptors
    }
}
