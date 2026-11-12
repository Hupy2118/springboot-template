package com.devagentstudio.template.engine.service.appupdate;

import com.devagentstudio.template.engine.service.ApiExceptionHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.InputStream;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AppUpdateHttpTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ENDPOINT = "/v1/app-updates/check";

    @Test
    void matchesTheReleaseCheckFixturesIncludingAnExplicitNullUrl() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/fixtures/app-update-check.json")) {
            assertNotNull(input);
            for (JsonNode fixture : JSON.readTree(input)) {
                MockMvc mvc = mvc(fixture.path("latestAppVersion").asText(), "https://cdn.example.com/releases/");
                MvcResult result = mvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON)
                                .content(JSON.writeValueAsBytes(fixture.get("request"))))
                        .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                        .andReturn();
                assertEquals(fixture.get("response"), JSON.readTree(result.getResponse().getContentAsByteArray()),
                        fixture.path("name").asText());
            }
        }
    }

    @Test
    void rejectsMissingExtraAndWronglyTypedFieldsWithoutScalarCoercion() throws Exception {
        MockMvc mvc = mvc("1.2.0", "https://cdn.example.com");
        for (String request : Arrays.asList("", "null", "{}", "[]", "\"1.0.0\"", "{",
                "{\"currentVersion\":null}", "{\"currentVersion\":123}", "{\"currentVersion\":true}",
                "{\"currentVersion\":[]}", "{\"currentVersion\":{}}",
                "{\"currentVersion\":\"1.0.0\",\"platform\":\"win32\"}",
                "{\"version\":\"1.0.0\"}")) {
            assertError(mvc, request, 400, "BAD_REQUEST");
        }
    }

    @Test
    void rejectsInvalidSemverAtTheHttpBoundary() throws Exception {
        MockMvc mvc = mvc("1.2.0", "https://cdn.example.com");
        for (String version : Arrays.asList("", "1.0", "v1.0.0", "01.0.0", "1.0.0-beta.01", "1.0.0 ")) {
            String request = JSON.createObjectNode().put("currentVersion", version).toString();
            assertError(mvc, request, 400, "BAD_REQUEST");
        }
    }

    @Test
    void returnsUnavailableWhenNoReleaseIsConfigured() throws Exception {
        MockMvc mvc = mvc(null, null);
        assertError(mvc, "{\"currentVersion\":\"1.0.0\"}", 503, "APP_UPDATE_UNAVAILABLE");
        assertError(mvc, "{\"currentVersion\":\"invalid\"}", 400, "BAD_REQUEST");
    }

    private static MockMvc mvc(String latestVersion, String downloadUrl) {
        AppUpdateProperties properties = new AppUpdateProperties();
        properties.setLatestAppVersion(latestVersion);
        properties.setAppDownloadUrl(downloadUrl);
        properties.afterPropertiesSet();
        return MockMvcBuilders.standaloneSetup(new AppUpdateController(new AppUpdateService(properties)))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    private static void assertError(MockMvc mvc, String request, int expectedStatus, String code) throws Exception {
        MvcResult result = mvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();
        assertEquals(expectedStatus, result.getResponse().getStatus(), request);
        JsonNode body = JSON.readTree(result.getResponse().getContentAsByteArray());
        assertEquals(4, body.size());
        assertEquals(code, body.path("code").asText(), request);
        assertTrue(body.path("message").isTextual());
        assertTrue(body.path("details").isObject());
        assertTrue(body.path("traceId").asText().matches("[0-9a-f-]{36}"));
    }
}
