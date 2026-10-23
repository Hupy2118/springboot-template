package com.cmbchina.backend.standalone.login;

import com.cmb.oa.one.account.oauth.client.login.context.TokenContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import javax.servlet.http.Cookie;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SsoLoginMvcTest {
    private MockEnvironment environment(String users) {
        return new MockEnvironment().withProperty("server.address", "127.0.0.1")
                .withProperty("standalone.mock.signing-key", "test-signing-key-at-least-32-characters")
                .withProperty("standalone.mock.login-secret", "test-login-secret-at-least-32-characters")
                .withProperty("standalone.mock.users-json", users);
    }
    private MockMvc mvc(MockIdentityService service) {
        return MockMvcBuilders.standaloneSetup(new MockLoginController(service))
                .addFilters(new MockIdentityFilter(service)).build();
    }
    @Test void frontendCallbackReturnsIdentityAndCookieAuthenticatesTheNextRequest() throws Exception {
        MockIdentityService service = new MockIdentityService(environment(
                "{\"member-001\":{\"userName\":\"Local Admin\",\"avatar\":\"/avatar.svg\"},\"member-002\":\"Member\"}"), new ObjectMapper());
        MockMvc mvc = mvc(service);
        MvcResult result = mvc.perform(get("/api/sso/login").param("code", "member-002"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.returnCode").value("SUC0000"))
                .andExpect(jsonPath("$.body.userId").value("member-001"))
                .andExpect(jsonPath("$.body.userName").value("Local Admin"))
                .andExpect(jsonPath("$.body.avatar").value("/avatar.svg")).andReturn();
        String header = result.getResponse().getHeader("Set-Cookie");
        assertNotNull(header); assertTrue(header.contains("HttpOnly")); assertTrue(header.contains("SameSite=Lax"));
        assertTrue(header.contains("Path=/")); assertTrue(header.contains("Max-Age=3600"));
        String token = header.substring(header.indexOf('=') + 1, header.indexOf(';'));
        assertEquals("member-001", service.verify(token));
        mvc.perform(get("/api/standalone/me").cookie(new Cookie("standalone_identity", token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.body.userId").value("member-001"));
        assertNull(TokenContextHolder.get());
        mvc.perform(get("/api/standalone/me").cookie(new Cookie("standalone_identity", token + "invalid")))
                .andExpect(status().isUnauthorized());
    }
    @Test void missingEmptyOrOversizedCodeDoesNotIssueACookie() throws Exception {
        MockMvc mvc = mvc(new MockIdentityService(environment("{\"member-001\":\"Admin\"}"), new ObjectMapper()));
        mvc.perform(get("/api/sso/login")).andExpect(status().isBadRequest()).andExpect(header().doesNotExist("Set-Cookie"));
        for (String code : new String[] {"", "  ", new String(new char[2049]).replace('\0', 'a')})
            mvc.perform(get("/api/sso/login").param("code", code)).andExpect(status().isBadRequest())
                    .andExpect(header().doesNotExist("Set-Cookie"));
    }
    @Test void selectedUserAndOptionalAvatarPreserveExistingStringConfiguration() throws Exception {
        MockEnvironment env = environment("{\"member-001\":\"Admin\",\"member-002\":\"Member\"}")
                .withProperty("standalone.mock.sso-user-id", "member-002");
        MockIdentityService service = new MockIdentityService(env, new ObjectMapper());
        mvc(service).perform(get("/api/sso/login").param("code", "mock-code").secure(true))
                .andExpect(status().isOk()).andExpect(jsonPath("$.body.userId").value("member-002"))
                .andExpect(jsonPath("$.body.userName").value("Member"))
                .andExpect(jsonPath("$.body.avatar").doesNotExist());
        assertThrows(IllegalArgumentException.class, () -> new MockIdentityService(
                env.withProperty("standalone.mock.sso-user-id", "unconfigured"), new ObjectMapper()));
    }
}
