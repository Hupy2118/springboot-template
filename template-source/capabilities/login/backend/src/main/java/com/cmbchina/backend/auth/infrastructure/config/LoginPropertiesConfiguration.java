package com.cmbchina.backend.auth.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(MockLoginProperties.class)
public class LoginPropertiesConfiguration {
}
