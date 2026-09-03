package com.cmbchina.backend.auth.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** 本地模拟登录签发的 JWT Cookie 配置。 */
@Data
@ConfigurationProperties(prefix = "xcodeagent.authorization.mock-login")
public class MockLoginProperties {

    private String cookieName = "token";
    private String secret = "local-mock-login-secret-please-change-in-shared-environments";
    private String issuer = "springboot-template-mock-login";
    private Duration ttl = Duration.ofHours(8);
    private boolean secure;
    private String sameSite = "Lax";
}
