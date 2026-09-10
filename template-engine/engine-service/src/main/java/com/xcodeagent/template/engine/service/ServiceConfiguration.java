package com.xcodeagent.template.engine.service;

import com.xcodeagent.template.engine.core.v2.ReconcileDecisionEngine;
import com.xcodeagent.template.engine.core.v2.TemplateRelease;
import com.xcodeagent.template.engine.core.v2.V2ProjectGenerator;
import com.xcodeagent.template.engine.source.CapabilityV2Loader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Paths;

@Configuration
public class ServiceConfiguration {
    @Bean
    TemplateRelease templateRelease(TemplateEngineProperties properties) {
        if (properties.getSourceRoot() == null || properties.getSourceRoot().trim().isEmpty()) throw new ServiceException("TEMPLATE_SOURCE_INVALID", "xcodeagent.template-engine.source-root is required", 500);
        return new CapabilityV2Loader().load(Paths.get(properties.getSourceRoot()));
    }

    @Bean
    ReconcileDecisionEngine reconcileDecisionEngine() { return new ReconcileDecisionEngine(); }

    @Bean
    V2ProjectGenerator v2ProjectGenerator(TemplateEngineProperties properties, TemplateRelease release) {
        return new V2ProjectGenerator(Paths.get(properties.getSourceRoot()), release);
    }

    @Bean
    TokenAuthenticator tokenAuthenticator(TemplateEngineProperties properties) {
        return new TokenAuthenticator(properties.getPrincipals());
    }
}
