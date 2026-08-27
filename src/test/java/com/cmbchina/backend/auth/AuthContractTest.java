package com.cmbchina.backend.auth;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthContractTest {

    @Test
    void contractExcludesDataPermissionsAndTemporarySecurityDefinitions() throws Exception {
        Path contractPath = Paths.get("docs", "auth", "contracts", "authorization-api.v2.yaml");
        byte[] source = Files.readAllBytes(contractPath);

        String yaml = new String(source, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(yaml.contains("authorization-api.v2"));
        assertFalse(yaml.contains("includeDeleted"));
        assertTrue(yaml.contains("当前返回固定 mock 成员数据"));
        assertTrue(yaml.contains("/api/authorization/mock-login"));
        assertTrue(yaml.contains("mockAuthorizationLogin"));
        assertTrue(yaml.contains("/api/authorization/roles/{roleId}/members"));
        assertTrue(yaml.contains("bindAuthorizationRoleMembers"));
        assertFalse(yaml.contains("/api/authorization/members/{memberId}"));
        assertFalse(yaml.contains("policyKey"));
        assertFalse(yaml.contains("expectedRevision"));
        assertFalse(yaml.contains("/api/authorization/audit"));
        assertFalse(yaml.contains("enum: [system, page, operation, data]"));
        assertTrue(yaml.contains("ResponseEnvelope:"));
        assertTrue(yaml.contains("required: [total, list, current, pageSize, totalPage]"));
        assertTrue(yaml.contains("returnCode:"));
        assertTrue(yaml.contains("^XCD1B[0-9]{2}$"));
        assertFalse(yaml.contains("cookieAuth"));
        assertFalse(yaml.contains("Unauthenticated:"));
        assertFalse(yaml.contains("Forbidden:"));
        assertFalse(yaml.contains("NotReady:"));
        assertFalse(yaml.contains("authentication_not_configured"));
        assertFalse(yaml.contains("'503':"));
        assertFalse(yaml.contains("'204':"));
    }

}
