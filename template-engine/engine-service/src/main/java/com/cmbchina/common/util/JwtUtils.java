package com.cmbchina.common.util;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.regex.Pattern;

public final class JwtUtils {
    private static final String HMAC = "HmacSHA256";
    private static final Pattern JWT = Pattern.compile("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");

    private static final ObjectReader JSON = new ObjectMapper().readerFor(JsonNode.class)
            .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private JwtUtils() {
    }

    /**
     * Verifies an HS256 signature and returns the signed claims for caller-specific validation.
     */
    public static JsonNode verifyHs256(String token, String secret) {
        if (token == null || !JWT.matcher(token).matches()) {
            throw new IllegalArgumentException("invalid compact JWT");
        }
        byte[] key = secret == null || secret.trim().isEmpty() ? null : secret.getBytes(StandardCharsets.UTF_8);
        String[] parts = token.split("\\.", -1);
        Base64.Decoder decoder = Base64.getUrlDecoder();
        try {
            byte[] signature = decoder.decode(parts[2]);
            if (!MessageDigest.isEqual(sign(parts[0] + "." + parts[1], key), signature)) {
                throw new IllegalArgumentException("invalid JWT signature");
            }

            JsonNode header = JSON.readValue(decoder.decode(parts[0]));
            if (header == null || !"HS256".equals(header.path("alg").textValue())
                    || header.has("crit") || header.has("b64")) {
                throw new IllegalArgumentException("unsupported JWT header");
            }

            JsonNode claims = JSON.readValue(decoder.decode(parts[1]));
            if (claims == null || !claims.isObject()) {
                throw new IllegalArgumentException("JWT claims must be a JSON object");
            }
            return claims;
        } catch (IOException exception) {
            throw new IllegalArgumentException("invalid JWT JSON", exception);
        }
    }

    private static byte[] sign(String input, byte[] key) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(key, HMAC));
            return mac.doFinal(input.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("cannot verify JWT signature", exception);
        }
    }
}
