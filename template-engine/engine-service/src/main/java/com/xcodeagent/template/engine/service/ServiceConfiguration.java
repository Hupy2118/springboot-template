package com.xcodeagent.template.engine.service;

import com.xcodeagent.template.engine.core.TemplateEngine;
import com.xcodeagent.template.engine.source.TemplateSourceLoader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Paths;

@Configuration
public class ServiceConfiguration {
    @Bean
    TemplateEngine templateEngine(TemplateEngineProperties properties) {
        if (properties.getSourceRoot() == null || properties.getSourceRoot().trim().isEmpty()) {
            throw new ServiceException("TEMPLATE_SOURCE_INVALID", "xcodeagent.template-engine.source-root is required", 500);
        }
        return new TemplateEngine(new TemplateSourceLoader().load(Paths.get(properties.getSourceRoot())));
    }

    @Bean
    TokenAuthenticator tokenAuthenticator(TemplateEngineProperties properties) {
        return new TokenAuthenticator(properties.getPrincipals());
    }
}
