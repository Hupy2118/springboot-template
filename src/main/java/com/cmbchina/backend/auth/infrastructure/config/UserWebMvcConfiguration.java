package com.cmbchina.backend.auth.infrastructure.config;

import com.cmbchina.backend.auth.common.interceptor.ResourcePermissionInterceptor;
import com.cmbchina.backend.auth.common.interceptor.UserWebMvcInterceptor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 注册登录人上下文拦截器。 */
@Configuration
@EnableConfigurationProperties(MockLoginProperties.class)
public class UserWebMvcConfiguration implements WebMvcConfigurer {

    private final UserWebMvcInterceptor userWebMvcInterceptor;
    private final ResourcePermissionInterceptor resourcePermissionInterceptor;

    public UserWebMvcConfiguration(UserWebMvcInterceptor userWebMvcInterceptor,
                                   ResourcePermissionInterceptor resourcePermissionInterceptor) {
        this.userWebMvcInterceptor = userWebMvcInterceptor;
        this.resourcePermissionInterceptor = resourcePermissionInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(userWebMvcInterceptor).addPathPatterns("/**").order(0);
        registry.addInterceptor(resourcePermissionInterceptor).addPathPatterns("/**").order(1);
    }
}
