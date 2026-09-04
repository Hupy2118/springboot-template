package com.xcodeagent.template.engine.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "xcodeagent.template-engine")
public class TemplateEngineProperties {
    private String sourceRoot;
    private List<Principal> principals = new ArrayList<Principal>();

    public String getSourceRoot() { return sourceRoot; }
    public void setSourceRoot(String sourceRoot) { this.sourceRoot = sourceRoot; }
    public List<Principal> getPrincipals() { return principals; }
    public void setPrincipals(List<Principal> principals) { this.principals = principals; }

    public static class Principal {
        private String principalId;
        private String principalType;
        private String tokenSha256;
        private List<String> scopes = new ArrayList<String>();
        public String getPrincipalId() { return principalId; }
        public void setPrincipalId(String principalId) { this.principalId = principalId; }
        public String getPrincipalType() { return principalType; }
        public void setPrincipalType(String principalType) { this.principalType = principalType; }
        public String getTokenSha256() { return tokenSha256; }
        public void setTokenSha256(String tokenSha256) { this.tokenSha256 = tokenSha256; }
        public List<String> getScopes() { return scopes; }
        public void setScopes(List<String> scopes) { this.scopes = scopes; }
    }
}
