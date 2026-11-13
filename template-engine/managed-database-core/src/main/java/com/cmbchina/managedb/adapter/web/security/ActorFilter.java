package com.cmbchina.managedb.adapter.web.security;

import com.cmbchina.managedb.domain.Actor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * 在密钥校验通过后解析 Studio 注入的最终用户头，放入请求属性。
 * 头缺失或非法时降级为 system，不拒绝请求（身份是审计增强，不是鉴权）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ActorFilter extends OncePerRequestFilter {
    public static final String ACTOR_ATTRIBUTE = "managed-database.actor";

    /**
     * 只作用于受服务密钥保护的 /managed-database/ 业务路径，与 ServiceKeyFilter 一致。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getServletPath().startsWith("/managed-database/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        request.setAttribute(ACTOR_ATTRIBUTE, Actor.of(
                decode(request.getHeader("X-Managed-User-Id")),
                decode(request.getHeader("X-Managed-User-Name"))));
        chain.doFilter(request, response);
    }

    /**
     * URL 解码；非法编码原样返回，不让身份解析抛异常中断请求。
     */
    private static String decode(String value) throws java.io.UnsupportedEncodingException {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (IllegalArgumentException e) {
            return value;
        }
    }
}
