package com.devagentstudio.template.engine.service;

import com.devagentstudio.template.engine.EngineServiceApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@EnabledIfEnvironmentVariable(named = "RUN_COMBINED_LOCAL_IT", matches = "true")
@SpringBootTest(classes = EngineServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.profiles.active=dev,managed-db", "server.port=0"})
class CombinedLocalIT {
    @Autowired
    private TestRestTemplate http;

    @Test
    void wrapsManagedDatabaseButLeavesEngineErrorsInTheirOwnContract() {
        HttpHeaders auth = new HttpHeaders();
        auth.setBearerAuth(System.getenv("MANAGED_DATABASE_SERVICE_KEY"));
        ResponseEntity<Map> health = http.exchange("/managed-database/health", HttpMethod.GET,
                new HttpEntity<>(auth), Map.class);
        assertEquals(HttpStatus.OK, health.getStatusCode());
        assertEquals(MediaType.APPLICATION_JSON, health.getHeaders().getContentType());
        assertBee(health, "SUC0000");
        assertEquals("", health.getBody().get("errorMsg"));
        assertEquals("UP", ((Map) health.getBody().get("body")).get("status"));

        ResponseEntity<Map> unauthorized = http.getForEntity("/managed-database/health", Map.class);
        assertEquals(HttpStatus.UNAUTHORIZED, unauthorized.getStatusCode());
        assertBee(unauthorized, "UNAUTHENTICATED");
        assertEquals(null, unauthorized.getBody().get("body"));

        HttpHeaders json = new HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);
        json.setAccept(Arrays.asList(MediaType.valueOf("application/zip"), MediaType.APPLICATION_JSON));
        ResponseEntity<Map> malformed = http.postForEntity("/v1/generate", new HttpEntity<>("{", json), Map.class);
        assertEquals(HttpStatus.BAD_REQUEST, malformed.getStatusCode());
        assertNotNull(malformed.getBody());
        assertEquals(new HashSet<>(Arrays.asList("code", "message", "details", "traceId")), malformed.getBody().keySet());
        assertEquals("BAD_REQUEST", malformed.getBody().get("code"));
    }

    private void assertBee(ResponseEntity<Map> response, String code) {
        assertNotNull(response.getBody());
        assertEquals(new HashSet<>(Arrays.asList("returnCode", "errorMsg", "body")), response.getBody().keySet());
        assertEquals(code, response.getBody().get("returnCode"));
    }
}
