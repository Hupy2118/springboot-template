package com.xcodeagent.template.engine.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "xcodeagent.template-engine")
public class TemplateEngineProperties {
    private String sourceRoot;

    public String getSourceRoot() { return sourceRoot; }
    public void setSourceRoot(String sourceRoot) { this.sourceRoot = sourceRoot; }
}
