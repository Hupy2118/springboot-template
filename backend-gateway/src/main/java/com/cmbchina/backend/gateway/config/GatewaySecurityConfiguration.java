package com.cmbchina.backend.gateway.config;

import com.cmbchina.backend.common.security.XcodeUserInfoTokenCodec;
import com.cmbchina.backend.gateway.security.PublicAuthenticationAdapter;
import java.util.Arrays;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** 装配 Gateway 认证与内部身份组件，并阻止本地调试认证进入部署环境。 */
@Configuration
@EnableConfigurationProperties(GatewaySecurityProperties.class)
public class GatewaySecurityConfiguration {
    /** 创建本地开发用的签名身份编解码器。 */
    @Bean
    public XcodeUserInfoTokenCodec xcodeUserInfoTokenCodec(GatewaySecurityProperties properties) {
        return new XcodeUserInfoTokenCodec(properties.getInternalIdentitySecret(),
                properties.getIssuer(), properties.getAudience(), properties.getApplicationId());
    }

    /** 校验认证模式必须存在适配器，并限制 local 模式只能在 local Profile 下运行。 */
    @Bean
    public ApplicationRunner authenticationModeGuard(Environment environment,
                                                       GatewaySecurityProperties properties,
                                                       ObjectProvider<PublicAuthenticationAdapter> adapters) {
        return arguments -> {
            boolean localProfile = Arrays.stream(environment.getActiveProfiles())
                    .anyMatch(profile -> "local".equalsIgnoreCase(profile));
            boolean localMode = "local".equalsIgnoreCase(properties.getMode());
            if (localMode != localProfile) {
                throw new IllegalStateException("gateway.security.mode=local 只能与 local Profile 同时使用。");
            }
            if (!adapters.orderedStream().findFirst().isPresent()) {
                throw new IllegalStateException("当前认证模式没有可用的 PublicAuthenticationAdapter。");
            }
        };
    }
}
