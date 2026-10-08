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
    void publishesOnlyTheV3ContractAtTheFormalEndpoints() throws Exception {
        JsonNode api = openApi();
        assertEquals("v3", api.path("info").path("version").asText());
        assertFalse(api.at("/paths/~1v1~1generate/post").isMissingNode());
        assertFalse(api.at("/paths/~1v1~1update/post").isMissingNode());
        assertEquals(2, api.path("paths").size());
        assertEquals("3", api.at("/components/schemas/UpdateRequest/properties/protocolVersion/enum/0").asText());
        assertEquals(3, api.at("/components/schemas/TemplateState/properties/schemaVersion/enum/0").asInt());
        assertEquals("application/zip", api.at("/paths/~1v1~1generate/post/responses/200/content").fieldNames().next());
        assertTrue(api.at("/paths/~1v1~1update/post/responses").has("204"));
        assertFalse(api.at("/paths/~1v1~1update/post/responses/204").has("content"));
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
