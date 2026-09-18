package com.cmbchina.backend.gateway.mock;

import com.cmbchina.backend.common.security.XcodePrincipal;
import com.cmbchina.backend.gateway.config.GatewaySecurityProperties;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** 为行外开发提供单用户、内存态且可退出的 Mock 登录会话。 */
@Service
@Profile("local")
@ConditionalOnProperty(prefix = "gateway.security", name = "mode", havingValue = "local")
public class MockAuthenticationService {
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, Long> sessions = new ConcurrentHashMap<>();
    private final GatewaySecurityProperties properties;
    private final MockUserRepository userRepository;

    /** 注入 Mock 会话配置和本地用户数据仓库。 */
    public MockAuthenticationService(GatewaySecurityProperties properties,
                                     MockUserRepository userRepository) {
        this.properties = properties;
        this.userRepository = userRepository;
    }

    /** 为固定 Mock 用户创建随机访问令牌。 */
    public LoginResult login() {
        byte[] random = new byte[32];
        secureRandom.nextBytes(random);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        long expiresAt = Instant.now().getEpochSecond() + properties.getMockTokenTtlSeconds();
        sessions.put(token, expiresAt);
        return new LoginResult(token, expiresAt, userRepository.defaultUser());
    }

    /** 校验本地令牌并恢复唯一 Mock 用户。 */
    public XcodePrincipal authenticate(String token) {
        Long expiresAt = token == null ? null : sessions.get(token);
        if (expiresAt == null || expiresAt <= Instant.now().getEpochSecond()) {
            if (token != null) {
                sessions.remove(token);
            }
            return null;
        }
        return userRepository.defaultUser();
    }

    /** 使当前本地令牌立即失效。 */
    public void logout(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    /** 返回 Mock 登录令牌、到期时间和固定用户。 */
    public static class LoginResult {
        private final String accessToken;
        private final long expiresAt;
        private final XcodePrincipal user;

        /** 创建不可变登录结果。 */
        public LoginResult(String accessToken, long expiresAt, XcodePrincipal user) {
            this.accessToken = accessToken;
            this.expiresAt = expiresAt;
            this.user = user;
        }

        /** 返回访问令牌。 */
        public String getAccessToken() { return accessToken; }

        /** 返回令牌类型。 */
        public String getTokenType() { return "Bearer"; }

        /** 返回到期时间戳。 */
        public long getExpiresAt() { return expiresAt; }

        /** 返回固定用户。 */
        public XcodePrincipal getUser() { return user; }
    }
}
