package com.xcodeagent.template.engine.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Deployment-configured, in-memory authentication only; no token value is logged or retained. */
final class TokenAuthenticator {
    private static final Set<String> ALLOWED_SCOPES = scopes("template.plan", "template.generate", "template.update");
    private final List<TemplateEngineProperties.Principal> principals;

    TokenAuthenticator(List<TemplateEngineProperties.Principal> principals) {
        if (principals == null || principals.isEmpty()) throw startup("at least one principal is required");
        Set<String> ids = new HashSet<String>();
        Set<String> digests = new HashSet<String>();
        for (TemplateEngineProperties.Principal principal : principals) {
            if (blank(principal.getPrincipalId()) || !ids.add(principal.getPrincipalId())) throw startup("principal-id must be unique");
            if (!"XCODE_AGENT".equals(principal.getPrincipalType())) throw startup("unknown principal-type");
            if (principal.getTokenSha256() == null || !principal.getTokenSha256().matches("[0-9a-f]{64}") || !digests.add(principal.getTokenSha256())) throw startup("token-sha256 must be unique lowercase SHA-256");
            if (principal.getScopes() == null || principal.getScopes().isEmpty()) throw startup("principal scopes are required");
            for (String scope : principal.getScopes()) if (!ALLOWED_SCOPES.contains(scope)) throw startup("unknown scope");
        }
        this.principals = principals;
    }

    void require(String authorization, String scope) {
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() == 7) throw new ServiceException("UNAUTHORIZED", "valid Bearer authorization is required", 401);
        String digest = sha256(authorization.substring(7));
        TemplateEngineProperties.Principal match = null;
        for (TemplateEngineProperties.Principal candidate : principals) {
            if (MessageDigest.isEqual(candidate.getTokenSha256().getBytes(StandardCharsets.US_ASCII), digest.getBytes(StandardCharsets.US_ASCII))) match = candidate;
        }
        if (match == null) throw new ServiceException("UNAUTHORIZED", "valid Bearer authorization is required", 401);
        if (!match.getScopes().contains(scope)) throw new ServiceException("FORBIDDEN", "principal lacks required scope", 403);
    }

    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64); for (byte b : bytes) out.append(String.format("%02x", b & 0xff)); return out.toString();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static Set<String> scopes(String... values) { Set<String> result = new HashSet<String>(); for (String value : values) result.add(value); return result; }
    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
    private static ServiceException startup(String message) { return new ServiceException("CONFIG_INVALID", message, 500); }
}
