package com.cmbchina.backend.common.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** 为行外本地开发签发和验证 compact JWS 格式的 Xcode-User-Info。 */
public class XcodeUserInfoTokenCodec {
    private static final String VERSION = "xcode-user-info.v1";
    private static final String ALGORITHM = "HmacSHA256";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final byte[] secret;
    private final String issuer;
    private final String audience;
    private final String applicationId;

    /** 使用本地开发密钥和固定身份域创建编解码器。 */
    public XcodeUserInfoTokenCodec(String secret, String issuer, String audience, String applicationId) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("Xcode-User-Info 本地签名密钥至少需要 32 字节。");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.issuer = requireText(issuer, "issuer");
        this.audience = requireText(audience, "audience");
        this.applicationId = requireText(applicationId, "applicationId");
    }

    /** 为当前精确路由签发短时内部用户身份。 */
    public String issue(XcodePrincipal principal, String endpointId, String traceId, long ttlSeconds) {
        if (principal == null || ttlSeconds <= 0 || ttlSeconds > 120) {
            throw new IllegalArgumentException("内部身份签发参数无效。");
        }
        long issuedAt = Instant.now().getEpochSecond();
        ObjectNode header = objectMapper.createObjectNode();
        header.put("alg", "HS256");
        header.put("typ", "JWT");
        header.put("kid", "local-mock");
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("version", VERSION);
        payload.put("iss", issuer);
        payload.put("aud", audience);
        payload.put("applicationId", applicationId);
        payload.put("principalType", "user");
        payload.put("sub", principal.getUserId());
        putOptional(payload, "employeeId", principal.getEmployeeId());
        putOptional(payload, "sapId", principal.getSapId());
        putOptional(payload, "enterpriseId", principal.getEnterpriseId());
        payload.put("actor", "gateway");
        ArrayNode allowed = payload.putArray("allowedEndpointIds");
        allowed.add(requireText(endpointId, "endpointId"));
        payload.put("iat", issuedAt);
        payload.put("exp", issuedAt + ttlSeconds);
        payload.put("jti", UUID.randomUUID().toString());
        putOptional(payload, "traceId", traceId);
        try {
            String first = encode(objectMapper.writeValueAsBytes(header));
            String second = encode(objectMapper.writeValueAsBytes(payload));
            String signingInput = first + "." + second;
            return signingInput + "." + encode(sign(signingInput.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法签发 Xcode-User-Info。", exception);
        }
    }

    /** 验证签名、有效期、身份域和当前 Endpoint 绑定后恢复用户身份。 */
    public XcodePrincipal verify(String token, String endpointId) {
        try {
            if (token == null || token.length() > 8192) {
                throw new IllegalArgumentException("Xcode-User-Info 缺失或过长。");
            }
            String[] segments = token.split("\\.", -1);
            if (segments.length != 3) {
                throw new IllegalArgumentException("Xcode-User-Info 格式无效。");
            }
            String signingInput = segments[0] + "." + segments[1];
            byte[] expected = sign(signingInput.getBytes(StandardCharsets.US_ASCII));
            byte[] actual = Base64.getUrlDecoder().decode(segments[2]);
            if (!MessageDigest.isEqual(expected, actual)) {
                throw new IllegalArgumentException("Xcode-User-Info 签名无效。");
            }
            JsonNode header = objectMapper.readTree(Base64.getUrlDecoder().decode(segments[0]));
            JsonNode payload = objectMapper.readTree(Base64.getUrlDecoder().decode(segments[1]));
            long now = Instant.now().getEpochSecond();
            if (!"HS256".equals(text(header, "alg"))
                    || !VERSION.equals(text(payload, "version"))
                    || !issuer.equals(text(payload, "iss"))
                    || !audience.equals(text(payload, "aud"))
                    || !applicationId.equals(text(payload, "applicationId"))
                    || payload.path("iat").asLong(Long.MAX_VALUE) > now + 30
                    || payload.path("exp").asLong(0) <= now
                    || !contains(payload.path("allowedEndpointIds"), endpointId)) {
                throw new IllegalArgumentException("Xcode-User-Info Claims 无效。");
            }
            return new XcodePrincipal(text(payload, "sub"), text(payload, "employeeId"),
                    text(payload, "sapId"), text(payload, "enterpriseId"));
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Xcode-User-Info 无法解析。", exception);
        }
    }

    /** 计算 compact JWS 的 HMAC-SHA256 签名。 */
    private byte[] sign(byte[] value) throws Exception {
        Mac mac = Mac.getInstance(ALGORITHM);
        mac.init(new SecretKeySpec(secret, ALGORITHM));
        return mac.doFinal(value);
    }

    /** 将字节编码为无填充 Base64URL。 */
    private String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    /** 写入存在的可选字符串字段。 */
    private void putOptional(ObjectNode node, String name, String value) {
        if (value != null && !value.trim().isEmpty()) {
            node.put(name, value);
        }
    }

    /** 读取 JSON 字符串字段。 */
    private String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value == null || value.isNull() ? null : value.asText();
    }

    /** 判断数组是否包含当前精确 Endpoint 标识。 */
    private boolean contains(JsonNode values, String expected) {
        if (!values.isArray() || expected == null) {
            return false;
        }
        for (JsonNode value : values) {
            if (expected.equals(value.asText())) {
                return true;
            }
        }
        return false;
    }

    /** 要求配置值非空。 */
    private String requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空。");
        }
        return value;
    }
}
