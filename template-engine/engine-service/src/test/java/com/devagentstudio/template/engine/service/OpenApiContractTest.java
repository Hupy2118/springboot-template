package com.devagentstudio.template.engine.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenApiContractTest {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @Test
    void publishesTheTemplateAndAppUpdateEndpoints() throws Exception {
        JsonNode api = openApi();
        assertEquals("v3", api.path("info").path("version").asText());
        assertFalse(api.at("/paths/~1v1~1generate/post").isMissingNode());
        assertFalse(api.at("/paths/~1v1~1update/post").isMissingNode());
        assertFalse(api.at("/paths/~1v1~1app-updates~1check/post").isMissingNode());
        assertEquals(3, api.path("paths").size());
        assertEquals("3", api.at("/components/schemas/UpdateRequest/properties/protocolVersion/enum/0").asText());
        assertEquals(3, api.at("/components/schemas/TemplateState/properties/schemaVersion/enum/0").asInt());
        assertEquals("application/zip", api.at("/paths/~1v1~1generate/post/responses/200/content").fieldNames().next());
        assertTrue(api.at("/paths/~1v1~1update/post/responses").has("204"));
        assertFalse(api.at("/paths/~1v1~1update/post/responses/204").has("content"));
    }

    @Test
    void appUpdateContractRequiresExactObjectsAndPublishesUnavailableErrors() throws Exception {
        JsonNode api = openApi();
        JsonNode endpoint = api.at("/paths/~1v1~1app-updates~1check/post");
        assertEquals("application/json", endpoint.path("responses").path("200").path("content").fieldNames().next());
        assertTrue(endpoint.path("responses").has("400"));
        assertTrue(endpoint.path("responses").has("503"));
        JsonNode request = api.at("/components/schemas/AppUpdateRequest");
        assertEquals(1, request.path("properties").size());
        assertEquals("currentVersion", request.path("required").get(0).asText());
        assertFalse(request.path("additionalProperties").asBoolean(true));
        JsonNode response = api.at("/components/schemas/AppUpdateResponse");
        assertEquals(3, response.path("required").size());
        assertFalse(response.path("additionalProperties").asBoolean(true));
        assertTrue(response.at("/properties/appDownloadUrl/nullable").asBoolean());
        assertTrue(contains(api.at("/components/schemas/Error/properties/code/enum"), "APP_UPDATE_UNAVAILABLE"));
    }

    @Test
    void v3FixedObjectsRejectAdditionalPropertiesAndUseExactErrorCodes() throws Exception {
        JsonNode api = openApi();
        List<String> fixedObjects = Arrays.asList("GenerateRequest", "UpdateRequest", "CapabilityRequest",
                "CapabilityState", "InstalledArtifactState", "JavaAnnotationContributionState",
                "MavenContributionState", "TemplateState", "Error");
        for (String name : fixedObjects) {
            assertFalse(api.at("/components/schemas/" + name + "/additionalProperties").asBoolean(true), name);
        }
        JsonNode codes = api.at("/components/schemas/Error/properties/code/enum");
        assertTrue(contains(codes, "TEMPLATE_STATE_INVALID"));
        assertTrue(contains(codes, "TEMPLATE_RELEASE_DOWNGRADE_UNSUPPORTED"));
        assertTrue(contains(codes, "RECONCILE_STATE_CHANGE_REQUIRED"));
    }

    private static boolean contains(JsonNode array, String value) {
        for (JsonNode item : array) if (value.equals(item.asText())) return true;
        return false;
    }

    private static JsonNode openApi() throws Exception {
        try (InputStream input = OpenApiContractTest.class.getResourceAsStream("/openapi/engine-service-v1.yaml")) {
            assertNotNull(input, "OpenAPI resource");
            return YAML.readTree(input);
        }
    }
}
