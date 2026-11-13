package com.cmbchina.managedb.adapter.web.security;

import com.cmb.bee.web.entity.BeeResponseEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 在业务参数绑定前校验固定密钥，不把密钥写入日志或业务状态。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ServiceKeyFilter extends OncePerRequestFilter {
    private final String serviceKey;
    private final ObjectMapper mapper;

    /**
     * 从环境配置读取密钥，缺失时业务入口明确返回未配置。
     */
    public ServiceKeyFilter(@Value("${managed-database.service-key:}") String serviceKey,
                            ObjectMapper mapper) {
        this.serviceKey = serviceKey;
        this.mapper = mapper;
    }

    /**
     * 保护 Managed Database 路径，包括健康检查。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getServletPath().startsWith("/managed-database/");
    }

    /**
     * 缺密钥返回 503，缺失或错误的 Bearer 凭据返回 401。
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (serviceKey == null || serviceKey.trim().isEmpty()) {
            reject(response, 503, "RESOURCE_SERVICE_NOT_CONFIGURED", "服务密钥尚未配置");
            return;
        }
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")
                || !MessageDigest.isEqual(serviceKey.getBytes(StandardCharsets.UTF_8),
                header.substring(7).getBytes(StandardCharsets.UTF_8))) {
            response.setHeader("WWW-Authenticate", "Bearer");
            reject(response, 401, "UNAUTHENTICATED", "服务密钥缺失或错误");
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * 直接输出脱敏 JSON，不触发默认错误页面或回显 Authorization。
     */
    private void reject(HttpServletResponse response, int status, String code,
                        String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        mapper.writeValue(response.getOutputStream(), BeeResponseEntity.failed(code, message));
    }
}
