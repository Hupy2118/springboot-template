package com.cmbchina.backend.gateway.config;

import java.util.Arrays;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/** 使用精确 Origin 白名单配置 Gateway 跨域访问。 */
@Configuration
@EnableConfigurationProperties(GatewayCorsProperties.class)
public class GatewayCorsConfiguration {
    /** 创建不携带 Cookie 且来源受限的响应式 CORS 过滤器。 */
    @Bean
    public CorsWebFilter corsWebFilter(GatewayCorsProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.getAllowedOrigins());
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type", "X-Request-Id", "Xcode-Agent-Id"));
        configuration.setExposedHeaders(Arrays.asList("X-Request-Id"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return new CorsWebFilter(source);
    }
}
