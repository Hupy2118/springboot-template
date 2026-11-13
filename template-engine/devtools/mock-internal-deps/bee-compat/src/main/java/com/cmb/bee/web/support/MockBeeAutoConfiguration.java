package com.cmb.bee.web.support;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "managed-database", name = "enabled", havingValue = "true")
public class MockBeeAutoConfiguration {
    @Bean
    MockBeeResponseAdvice mockBeeResponseAdvice() {
        return new MockBeeResponseAdvice();
    }
}
