package com.cmbchina.backend.auth.adapter.web;

import com.cmbchina.backend.auth.application.service.MockJwtService;
import com.cmbchina.backend.auth.common.context.BaseUserData;
import com.cmbchina.backend.auth.infrastructure.config.MockLoginProperties;
import com.cmbchina.backend.common.response.ResponseEntity;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;

/** 仅供本地联调的独立模拟登录入口。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/login/mock")
public class MockLoginController {
    private final MockJwtService jwtService;
    private final MockLoginProperties properties;

    @PostMapping
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request, HttpServletResponse response) {
        BaseUserData user = new BaseUserData(request.memberId, request.memberName);
        ResponseCookie cookie = ResponseCookie.from(properties.getCookieName(), jwtService.createToken(user))
                .httpOnly(true).secure(properties.isSecure()).sameSite(properties.getSameSite())
                .path("/").maxAge(properties.getTtl()).build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return ResponseEntity.success(new LoginResponse(user.getMemberId(), user.getMemberName()));
    }

    @Data
    public static class LoginRequest { @NotBlank private String memberId; @NotBlank private String memberName; }
    @Data
    @RequiredArgsConstructor
    public static class LoginResponse { private final String memberId; private final String memberName; }
}
