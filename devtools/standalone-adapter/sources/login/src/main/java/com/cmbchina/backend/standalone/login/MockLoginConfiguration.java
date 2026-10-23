package com.cmbchina.backend.standalone.login;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

@Configuration
@Profile({"dev", "local"})
@ConditionalOnProperty(name="standalone.mock.enabled", havingValue="true")
public class MockLoginConfiguration {
    @Bean public MockIdentityService mockIdentityService(Environment env, ObjectMapper mapper) { return new MockIdentityService(env, mapper); }
    @Bean public FilterRegistrationBean<MockIdentityFilter> mockIdentityFilter(MockIdentityService identity) {
        FilterRegistrationBean<MockIdentityFilter> bean = new FilterRegistrationBean<MockIdentityFilter>(new MockIdentityFilter(identity));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        bean.addUrlPatterns("/*");
        return bean;
    }
}
