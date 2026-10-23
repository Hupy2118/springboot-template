package com.cmbchina.backend.standalone.login;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.env.Environment;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Development-only signed identities; RBAC still executes in the unchanged authorization extension. */
public final class MockIdentityService {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final String HEADER = ENCODER.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
    private static final String DEFAULT_USERS = "{\"member-001\":\"Local Admin\",\"member-002\":\"Local Member\"}";
    private final ObjectMapper mapper;
    private final byte[] key, loginSecret;
    private final Map<String, Map<String, String>> users = new LinkedHashMap<String, Map<String, String>>();
    private final String ssoUserId;

    public MockIdentityService(Environment env, ObjectMapper mapper) {
        String address = env.getProperty("server.address", "");
        if (!"127.0.0.1".equals(address) && !"::1".equals(address))
            throw new IllegalArgumentException("Mock login requires explicit loopback server.address");
        this.mapper = mapper;
        this.key = secret(env, "standalone.mock.signing-key");
        this.loginSecret = secret(env, "standalone.mock.login-secret");
        try {
            String configuredUsers = env.getProperty("standalone.mock.users-json", "");
            JsonNode node = mapper.readTree(configuredUsers.trim().isEmpty() ? DEFAULT_USERS : configuredUsers);
            if (!node.isObject() || node.size() == 0) throw new IllegalArgumentException();
            node.fields().forEachRemaining(entry -> {
                JsonNode value = entry.getValue();
                String name = value.isTextual() ? value.asText() : value.path("userName").asText("");
                if (!entry.getKey().matches("[A-Za-z0-9_-]{1,32}") || name.trim().isEmpty()
                        || (!value.isTextual() && !value.isObject())) throw new IllegalArgumentException();
                Map<String, String> identity = new LinkedHashMap<String, String>();
                identity.put("userId", entry.getKey()); identity.put("userName", name);
                if (value.isObject() && value.has("avatar")) {
                    if (!value.path("avatar").isTextual()) throw new IllegalArgumentException();
                    identity.put("avatar", value.path("avatar").asText());
                }
                users.put(entry.getKey(), identity);
            });
        } catch (Exception e) { throw new IllegalArgumentException("Mock users must map member IDs to names or userName/avatar objects"); }
        String selected = env.getProperty("standalone.mock.sso-user-id", "").trim();
        this.ssoUserId = selected.isEmpty() ? users.keySet().iterator().next() : selected;
        if (!users.containsKey(ssoUserId)) throw new IllegalArgumentException("Mock SSO user must be present in configured users");
    }

    private static byte[] secret(Environment env, String name) {
        String value = env.getProperty(name, "");
        if (value.isEmpty()) {
            byte[] generated = new byte[32];
            new SecureRandom().nextBytes(generated);
            return generated;
        }
        if (value.length() < 32) throw new IllegalArgumentException("Mock credentials require at least 32 characters: " + name);
        return value.getBytes(StandardCharsets.UTF_8);
    }

    public String login(String user, String secret) {
        if (secret == null || !MessageDigest.isEqual(loginSecret, secret.getBytes(StandardCharsets.UTF_8)) || !users.containsKey(user))
            throw new IllegalArgumentException("Invalid local credentials");
        return issueToken(user);
    }

    /** A dummy callback code never selects a user or represents enterprise authentication. */
    public String loginSso(String code) {
        if (code == null || code.trim().isEmpty() || code.length() > 2048)
            throw new IllegalArgumentException("A non-empty mock callback code is required");
        return issueToken(ssoUserId);
    }

    public Map<String, String> ssoIdentity() { return identity(ssoUserId); }

    private String issueToken(String user) {
        try {
            long now = Instant.now().getEpochSecond();
            Map<String, Object> claims = new LinkedHashMap<String, Object>();
            claims.put("iss", "standalone-local"); claims.put("aud", "backend"); claims.put("sub", user);
            claims.put("iat", now); claims.put("exp", now + 3600);
            String content = HEADER + "." + ENCODER.encodeToString(mapper.writeValueAsBytes(claims));
            return content + "." + ENCODER.encodeToString(sign(content));
        } catch (Exception e) { throw new IllegalStateException("Cannot issue local identity"); }
    }

    public String verify(String token) {
        if (token == null || token.length() > 4096) return null;
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3 || !HEADER.equals(parts[0])
                    || !MessageDigest.isEqual(sign(parts[0] + "." + parts[1]), DECODER.decode(parts[2]))) return null;
            JsonNode claims = mapper.readTree(DECODER.decode(parts[1]));
            long now = Instant.now().getEpochSecond();
            String user = claims.path("sub").asText();
            if (!"standalone-local".equals(claims.path("iss").asText()) || !"backend".equals(claims.path("aud").asText())
                    || !claims.path("exp").isIntegralNumber() || !claims.path("iat").isIntegralNumber()
                    || claims.path("exp").asLong() <= now || claims.path("iat").asLong() > now + 5
                    || claims.path("exp").asLong() - claims.path("iat").asLong() != 3600 || !users.containsKey(user)) return null;
            return user;
        } catch (Exception e) { return null; }
    }

    private byte[] sign(String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    }

    public Map<String, String> identity(String user) {
        return new LinkedHashMap<String, String>(users.get(user));
    }
}
