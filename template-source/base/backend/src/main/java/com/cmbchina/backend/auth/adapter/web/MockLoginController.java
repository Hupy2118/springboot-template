package com.cmbchina.backend.auth.adapter.web;

import com.cmbchina.backend.auth.application.dto.MemberDTO;
import com.cmbchina.backend.auth.application.dto.MockLoginRequest;
import com.cmbchina.backend.auth.application.service.MemberApplicationService;
import com.cmbchina.backend.auth.application.service.MockJwtService;
import com.cmbchina.backend.auth.infrastructure.config.MockLoginProperties;
import com.cmbchina.backend.common.response.ResponseEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;

/** 本地联调用模拟登录接口。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/authorization/mock-login")
public class MockLoginController {

    private final MemberApplicationService memberService;
    private final MockJwtService jwtService;
    private final MockLoginProperties properties;

    @PostMapping
    public ResponseEntity<MemberDTO> login(@Valid @RequestBody MockLoginRequest request,
                                           HttpServletResponse response) {
        MemberDTO member = memberService.getMockMember(request.getMemberId());
        String token = jwtService.createToken(member);
        ResponseCookie cookie = ResponseCookie.from(properties.getCookieName(), token)
                .httpOnly(true)
                .secure(properties.isSecure())
                .sameSite(properties.getSameSite())
                .path("/")
                .maxAge(properties.getTtl())
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return ResponseEntity.success(member);
    }
}
