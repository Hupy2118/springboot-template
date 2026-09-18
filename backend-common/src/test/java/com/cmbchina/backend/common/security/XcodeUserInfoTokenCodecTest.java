package com.cmbchina.backend.common.security;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证本地内部身份签名和精确 Endpoint 绑定。 */
class XcodeUserInfoTokenCodecTest {
    /** 正确路由可以恢复固定 Mock 用户，其他路由必须拒绝。 */
    @Test
    void verifiesOnlyTheBoundEndpoint() {
        XcodeUserInfoTokenCodec codec = new XcodeUserInfoTokenCodec(
                "local-development-secret-at-least-32-bytes", "gateway", "internal", "demo");
        XcodePrincipal principal = new XcodePrincipal("mock-user-001", "mock-employee-001",
                "mock-sap-001", "mock-enterprise");

        String token = codec.issue(principal, "orders:list", "trace-1", 60);

        assertEquals("mock-user-001", codec.verify(token, "orders:list").getName());
        assertThrows(IllegalArgumentException.class, () -> codec.verify(token, "orders:delete"));
    }
}
