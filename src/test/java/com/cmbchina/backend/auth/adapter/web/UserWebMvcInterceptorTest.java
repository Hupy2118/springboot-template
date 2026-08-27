package com.cmbchina.backend.auth.adapter.web;

import com.cmbchina.backend.auth.application.dto.MemberDTO;
import com.cmbchina.backend.auth.application.service.MockJwtService;
import com.cmbchina.backend.auth.common.interceptor.UserWebMvcInterceptor;
import com.cmbchina.backend.auth.infrastructure.config.MockLoginProperties;
import com.cmbchina.backend.auth.common.context.BaseUserData;
import com.cmbchina.backend.auth.common.context.BaseUserDataThreadHodler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.servlet.http.Cookie;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserWebMvcInterceptorTest {

    @AfterEach
    void cleanUp() {
        BaseUserDataThreadHodler.clear();
    }

    @Test
    void readsMockCookieAndClearsContextAfterRequest() throws Exception {
        MockLoginProperties properties = new MockLoginProperties();
        MockJwtService jwtService = new MockJwtService(new ObjectMapper(), properties);
        String token = jwtService.createToken(new MemberDTO("member-006", "杨静"));
        UserWebMvcInterceptor interceptor = new UserWebMvcInterceptor(jwtService, properties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(properties.getCookieName(), token));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));
        BaseUserData userData = BaseUserDataThreadHodler.get();
        assertEquals("member-006", userData.getMemberId());
        assertEquals("杨静", userData.getMemberName());

        interceptor.afterCompletion(request, response, new Object(), null);
        assertNull(BaseUserDataThreadHodler.get());
    }

    @Test
    void missingCookieRemovesStaleContext() {
        MockLoginProperties properties = new MockLoginProperties();
        UserWebMvcInterceptor interceptor = new UserWebMvcInterceptor(
                new MockJwtService(new ObjectMapper(), properties), properties);
        BaseUserDataThreadHodler.set(new BaseUserData("stale", "stale"));

        interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), new Object());

        assertNull(BaseUserDataThreadHodler.get());
    }
}
