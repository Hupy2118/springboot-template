package com.cmbchina.backend.auth.common.interceptor;

import com.cmbchina.backend.auth.application.service.MockJwtService;
import com.cmbchina.backend.auth.infrastructure.config.MockLoginProperties;
import com.cmbchina.backend.auth.common.context.BaseUserDataThreadHodler;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 从 mock JWT Cookie 建立当前请求登录人上下文。
 */
@Component
public class UserWebMvcInterceptor implements HandlerInterceptor {

    private final MockJwtService jwtService;
    private final MockLoginProperties properties;

    public UserWebMvcInterceptor(MockJwtService jwtService, MockLoginProperties properties) {
        this.jwtService = jwtService;
        this.properties = properties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request,
                             @NotNull HttpServletResponse response,
                             @NotNull Object handler) {
        BaseUserDataThreadHodler.clear();
        String token = findCookieValue(request.getCookies(), properties.getCookieName());
        jwtService.parseToken(token).ifPresent(BaseUserDataThreadHodler::set);
        return true;
    }

    @Override
    public void afterCompletion(@NotNull HttpServletRequest request,
                                @NotNull HttpServletResponse response,
                                @NotNull Object handler,
                                Exception ex) {
        BaseUserDataThreadHodler.clear();
    }

    private String findCookieValue(Cookie[] cookies, String cookieName) {
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (cookieName.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
