package com.cmbchina.backend.auth.application.service;

import com.cmbchina.backend.auth.application.dto.MemberDTO;
import com.cmbchina.backend.auth.infrastructure.config.MockLoginProperties;
import com.cmbchina.backend.auth.common.context.BaseUserData;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 仅用于模拟登录的 HS256 JWT 签发器。 */
@Service
@RequiredArgsConstructor
public class MockJwtService {

    private static final String HMAC_SHA_256 = "HmacSHA256";
    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();

    private final ObjectMapper objectMapper;
    private final MockLoginProperties properties;

    public String createToken(MemberDTO member) {
        Instant issuedAt = Instant.now();

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "HS256");
        header.put("typ", "JWT");

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", properties.getIssuer());
        claims.put("sub", member.getMemberId());
        claims.put("memberId", member.getMemberId());
        claims.put("memberName", member.getMemberName());
        claims.put("iat", issuedAt.getEpochSecond());
        claims.put("exp", issuedAt.plus(properties.getTtl()).getEpochSecond());
        claims.put("jti", UUID.randomUUID().toString());

        String content = encodeJson(header) + "." + encodeJson(claims);
        return content + "." + sign(content);
    }

    /** 校验并解析由本服务签发的 mock JWT。 */
    public Optional<BaseUserData> parseToken(String token) {
        if (token == null || token.trim().isEmpty()) {
            return Optional.empty();
        }
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3 || !hasValidSignature(parts)) {
                return Optional.empty();
            }

            JsonNode header = objectMapper.readTree(BASE64_URL_DECODER.decode(parts[0]));
            JsonNode claims = objectMapper.readTree(BASE64_URL_DECODER.decode(parts[1]));
            if (!"HS256".equals(header.path("alg").asText())
                    || !properties.getIssuer().equals(claims.path("iss").asText())
                    || !claims.path("exp").canConvertToLong()
                    || claims.path("exp").asLong() <= Instant.now().getEpochSecond()) {
                return Optional.empty();
            }

            String memberId = claims.path("memberId").asText();
            String memberName = claims.path("memberName").asText();
            if (memberId.isEmpty() || memberName.isEmpty()
                    || !memberId.equals(claims.path("sub").asText())) {
                return Optional.empty();
            }
            return Optional.of(new BaseUserData(memberId, memberName));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private boolean hasValidSignature(String[] parts) {
        byte[] expected = BASE64_URL_DECODER.decode(sign(parts[0] + "." + parts[1]));
        byte[] actual = BASE64_URL_DECODER.decode(parts[2]);
        return MessageDigest.isEqual(expected, actual);
    }

    private String encodeJson(Map<String, Object> value) {
        try {
            return BASE64_URL.encodeToString(objectMapper.writeValueAsBytes(value));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法生成模拟登录 JWT", e);
        }
    }

    private String sign(String content) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA_256);
            mac.init(new SecretKeySpec(properties.getSecret().getBytes(StandardCharsets.UTF_8), HMAC_SHA_256));
            return BASE64_URL.encodeToString(mac.doFinal(content.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            throw new IllegalStateException("无法签名模拟登录 JWT", e);
        }
    }
}
