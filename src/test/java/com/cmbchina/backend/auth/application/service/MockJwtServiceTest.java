package com.cmbchina.backend.auth.application.service;

import com.cmbchina.backend.auth.application.dto.MemberDTO;
import com.cmbchina.backend.auth.infrastructure.config.MockLoginProperties;
import com.cmbchina.backend.auth.common.context.BaseUserData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockJwtServiceTest {

    @Test
    void createsJwtWithMockMemberClaims() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        MockLoginProperties properties = new MockLoginProperties();
        String token = new MockJwtService(objectMapper, properties)
                .createToken(new MemberDTO("member-008", "赵敏"));

        String[] parts = token.split("\\.");
        assertEquals(3, parts.length);
        JsonNode claims = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertEquals("member-008", claims.get("sub").asText());
        assertEquals("member-008", claims.get("memberId").asText());
        assertEquals("赵敏", claims.get("memberName").asText());
        assertTrue(claims.get("exp").asLong() > claims.get("iat").asLong());
    }

    @Test
    void verifiesAndParsesIssuedToken() {
        MockJwtService service = new MockJwtService(new ObjectMapper(), new MockLoginProperties());
        String token = service.createToken(new MemberDTO("member-002", "李娜"));

        BaseUserData userData = service.parseToken(token).orElseThrow(AssertionError::new);

        assertEquals("member-002", userData.getMemberId());
        assertEquals("李娜", userData.getMemberName());
    }

    @Test
    void rejectsTamperedToken() {
        MockJwtService service = new MockJwtService(new ObjectMapper(), new MockLoginProperties());
        String token = service.createToken(new MemberDTO("member-002", "李娜"));

        assertTrue(!service.parseToken(token + "tampered").isPresent());
    }
}
