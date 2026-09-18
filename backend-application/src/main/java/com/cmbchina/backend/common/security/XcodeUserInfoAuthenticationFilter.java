package com.cmbchina.backend.common.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Principal;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 验证 Gateway 服务身份和签名用户上下文后向业务 Controller 暴露可信 Principal。 */
@Component
public class XcodeUserInfoAuthenticationFilter extends OncePerRequestFilter {
    private final XcodeUserInfoTokenCodec tokenCodec;
    private final String serviceToken;
    private final String protectedPrefix;

    /** 从受管配置创建 Backend 内部身份过滤器。 */
    public XcodeUserInfoAuthenticationFilter(
            @Value("${xcode.internal-identity.secret}") String secret,
            @Value("${xcode.internal-identity.issuer}") String issuer,
            @Value("${xcode.internal-identity.audience}") String audience,
            @Value("${xcode.internal-identity.application-id}") String applicationId,
            @Value("${xcode.internal-identity.service-token}") String serviceToken,
            @Value("${xcode.internal-identity.protected-prefix:/internal/}") String protectedPrefix) {
        this.tokenCodec = new XcodeUserInfoTokenCodec(secret, issuer, audience, applicationId);
        this.serviceToken = serviceToken;
        this.protectedPrefix = protectedPrefix;
    }

    /** 只保护 Gateway 启用时使用的 Backend 内部 Endpoint。 */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(protectedPrefix);
    }

    /** 验证双重内部身份并恢复只读 Principal。 */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String actualServiceToken = request.getHeader("Xcode-Service-Token");
        if (!constantTimeEquals(serviceToken, actualServiceToken)) {
            unauthorized(response, "内部服务身份无效。");
            return;
        }
        String endpointId = request.getHeader("Xcode-Endpoint-Id");
        try {
            XcodePrincipal principal = tokenCodec.verify(request.getHeader("Xcode-User-Info"), endpointId);
            HttpServletRequest wrapped = new HttpServletRequestWrapper(request) {
                /** 返回 Gateway 已验证的用户身份。 */
                @Override
                public Principal getUserPrincipal() {
                    return principal;
                }
            };
            wrapped.setAttribute("xcode.principal", principal);
            chain.doFilter(wrapped, response);
        } catch (IllegalArgumentException exception) {
            unauthorized(response, "内部用户身份无效。");
        }
    }

    /** 使用常量时间比较服务令牌。 */
    private boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    /** 返回稳定且不泄露内部细节的 401 响应。 */
    private void unauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":\"INTERNAL_IDENTITY_INVALID\",\"message\":\""
                + message + "\"}");
    }
}
