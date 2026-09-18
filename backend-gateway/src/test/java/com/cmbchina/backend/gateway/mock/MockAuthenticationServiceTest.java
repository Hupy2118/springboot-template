package com.cmbchina.backend.gateway.mock;

import com.cmbchina.backend.gateway.config.GatewaySecurityProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 验证单用户 Mock 登录与退出生命周期。 */
class MockAuthenticationServiceTest {
    /** 登录后可恢复固定用户，退出后同一令牌立即失效。 */
    @Test
    void logsInAndLogsOutTheFixedUser() {
        GatewaySecurityProperties properties = new GatewaySecurityProperties();
        MockAuthenticationService service = new MockAuthenticationService(
                properties, new MockUserRepository(new ObjectMapper()));

        MockAuthenticationService.LoginResult login = service.login();

        assertEquals("mock-user-001", service.authenticate(login.getAccessToken()).getName());
        service.logout(login.getAccessToken());
        assertNull(service.authenticate(login.getAccessToken()));
    }
}
