package com.cmbchina.backend.auth.adapter.web;

import com.cmbchina.backend.auth.application.dto.MemberDTO;
import com.cmbchina.backend.auth.application.dto.MockLoginRequest;
import com.cmbchina.backend.auth.application.service.MemberApplicationService;
import com.cmbchina.backend.auth.application.service.MockJwtService;
import com.cmbchina.backend.auth.infrastructure.config.MockLoginProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MockLoginControllerTest {

    @Test
    void writesJwtToHttpOnlyCookie() {
        MemberApplicationService memberService = mock(MemberApplicationService.class);
        MockJwtService jwtService = mock(MockJwtService.class);
        MockLoginProperties properties = new MockLoginProperties();
        MemberDTO member = new MemberDTO("member-001", "张伟");
        when(memberService.getMockMember("member-001")).thenReturn(member);
        when(jwtService.createToken(member)).thenReturn("header.payload.signature");
        MockLoginController controller = new MockLoginController(memberService, jwtService, properties);
        MockLoginRequest request = new MockLoginRequest();
        request.setMemberId("member-001");
        MockHttpServletResponse response = new MockHttpServletResponse();

        com.cmbchina.backend.common.response.ResponseEntity<MemberDTO> result =
                controller.login(request, response);

        assertEquals(member, result.getBody());
        String cookie = response.getHeader("Set-Cookie");
        assertTrue(cookie.startsWith(properties.getCookieName() + "=header.payload.signature"));
        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("SameSite=Lax"));
    }
}
