package com.cmb.bee.web.support;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class MockBeeAutoConfiguration {
    @Bean
    MockBeeResponseAdvice mockBeeResponseAdvice() {
        return new MockBeeResponseAdvice();
    }
}
