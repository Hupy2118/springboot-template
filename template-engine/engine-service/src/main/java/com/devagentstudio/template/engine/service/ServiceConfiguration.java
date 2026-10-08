package com.devagentstudio.template.engine.service;

import com.devagentstudio.template.engine.core.v3.CodeTemplateRelease;
import com.devagentstudio.template.engine.core.v3.ExtensionResolver;
import com.devagentstudio.template.engine.core.v3.V3ProjectMaterializer;
import com.devagentstudio.template.engine.core.v3.V3UpdatePlanner;
import com.devagentstudio.template.engine.source.v3.CodeTemplateReleaseLoader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Paths;

/** V3 service beans load exclusively from source-root/code. */
@Configuration
public class ServiceConfiguration {
    @Bean
    CodeTemplateRelease codeTemplateRelease(TemplateEngineProperties properties) {
        if (properties.getSourceRoot() == null || properties.getSourceRoot().trim().isEmpty())
            throw new ServiceException("TEMPLATE_SOURCE_INVALID", "devagentstudio.template-engine.source-root is required", 500);
        return new CodeTemplateReleaseLoader(Paths.get(properties.getSourceRoot()).resolve("code")).load();
    }

    @Bean ExtensionResolver extensionResolver(CodeTemplateRelease release) { return new ExtensionResolver(release); }
    @Bean V3ProjectMaterializer v3ProjectMaterializer(CodeTemplateRelease release) { return new V3ProjectMaterializer(release); }
    @Bean V3UpdatePlanner v3UpdatePlanner(CodeTemplateRelease release) { return new V3UpdatePlanner(release); }
}
