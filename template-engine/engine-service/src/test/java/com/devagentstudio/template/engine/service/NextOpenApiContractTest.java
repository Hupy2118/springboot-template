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

class NextOpenApiContractTest {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @Test
    void retainsV2AndPublishesV3PreviewWithoutCutover() throws Exception {
        JsonNode api = openApi();
        assertEquals("v2+v3-preview", api.path("info").path("version").asText());
        assertFalse(api.at("/paths/~1v1~1generate/post").isMissingNode());
        assertFalse(api.at("/paths/~1v1~1update/post").isMissingNode());
        assertFalse(api.at("/paths/~1v1~1generate-next/post").isMissingNode());
        assertFalse(api.at("/paths/~1v1~1update-next/post").isMissingNode());
        assertEquals(2, api.at("/components/schemas/TemplateStateV2/properties/schemaVersion/enum/0").asInt());
        assertEquals("3", api.at("/components/schemas/UpdateNextRequest/properties/protocolVersion/enum/0").asText());
        assertEquals(3, api.at("/components/schemas/TemplateStateV3/properties/schemaVersion/enum/0").asInt());
        assertEquals("application/zip", api.at("/paths/~1v1~1generate-next/post/responses/200/content").fieldNames().next());
        assertTrue(api.at("/paths/~1v1~1update-next/post/responses").has("204"));
        assertFalse(api.at("/paths/~1v1~1update-next/post/responses/204").has("content"));
    }

    @Test
    void v3FixedObjectsRejectAdditionalPropertiesAndUseExactErrorCodes() throws Exception {
        JsonNode api = openApi();
        List<String> fixedObjects = Arrays.asList("GenerateNextRequest", "UpdateNextRequest", "CapabilityRequestV3",
                "CapabilityStateV3", "InstalledArtifactStateV3", "JavaAnnotationContributionStateV3",
                "MavenContributionStateV3", "TemplateStateV3", "ErrorV3");
        for (String name : fixedObjects) {
            assertFalse(api.at("/components/schemas/" + name + "/additionalProperties").asBoolean(true), name);
        }
        JsonNode codes = api.at("/components/schemas/ErrorV3/properties/code/enum");
        assertTrue(contains(codes, "TEMPLATE_STATE_INVALID"));
        assertTrue(contains(codes, "TEMPLATE_RELEASE_DOWNGRADE_UNSUPPORTED"));
        assertTrue(contains(codes, "RECONCILE_STATE_CHANGE_REQUIRED"));
    }

    private static boolean contains(JsonNode array, String value) {
        for (JsonNode item : array) if (value.equals(item.asText())) return true;
        return false;
    }

    private static JsonNode openApi() throws Exception {
        try (InputStream input = NextOpenApiContractTest.class.getResourceAsStream("/openapi/engine-service-v1.yaml")) {
            assertNotNull(input, "OpenAPI resource");
            return YAML.readTree(input);
        }
    }
}
