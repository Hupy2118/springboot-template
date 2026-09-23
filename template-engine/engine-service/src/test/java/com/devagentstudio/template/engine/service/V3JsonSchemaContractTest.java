package com.devagentstudio.template.engine.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V3JsonSchemaContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void zipSchemasArePresentAndVersioned() throws Exception {
        for (String resource : Arrays.asList("template-state.schema.json", "operation.schema.json", "extension-update-package.schema.json")) {
            JsonNode schema = read(resource);
            assertEquals("https://json-schema.org/draft/2020-12/schema", schema.path("$schema").asText(), resource);
            assertEquals("object", schema.path("type").asText(), resource);
            assertFalse(schema.path("additionalProperties").asBoolean(true), resource);
        }
    }

    @Test
    void stateUsesResourceOwnershipAndOperationSchemaFreezesSixTypes() throws Exception {
        JsonNode state = read("template-state.schema.json");
        List<String> stateFields = Arrays.asList("schemaVersion", "templateRevision", "requested", "effective", "installedArtifacts", "managedContributions");
        for (String field : stateFields) assertTrue(contains(state.path("required"), field), field);
        JsonNode annotation = state.at("/$defs/javaAnnotationContribution/properties");
        JsonNode maven = state.at("/$defs/mavenContribution/properties");
        assertTrue(annotation.has("owners"));
        assertTrue(maven.has("owners"));
        assertFalse(annotation.has("capabilityId"));
        assertFalse(maven.has("capabilityId"));

        JsonNode operation = read("operation.schema.json");
        JsonNode types = operation.at("/properties/type/enum");
        assertEquals(6, types.size());
        for (String expected : Arrays.asList("ADD_FILE", "REPLACE_MANAGED_FILE", "ENSURE_JAVA_ANNOTATION",
                "REMOVE_JAVA_ANNOTATION", "ENSURE_MAVEN_DEPENDENCY", "REMOVE_MAVEN_DEPENDENCY")) {
            assertTrue(contains(types, expected), expected);
        }
        assertTrue(contains(operation.path("required"), "index"));
        assertTrue(contains(operation.path("required"), "operationId"));
    }

    @Test
    void packageSchemaUsesDeterministicMetadataAndStrictEmptyExtensions() throws Exception {
        JsonNode schema = read("extension-update-package.schema.json");
        for (String field : Arrays.asList("protocolVersion", "packageId", "mode", "sourceRevision", "currentStateDigest",
                "nextStateDigest", "operations", "validationPlan", "payloadManifest", "nextTemplateState", "diagnostics")) {
            assertTrue(contains(schema.path("required"), field), field);
        }
        assertEquals(0, schema.at("/properties/validationPlan/maxItems").asInt(-1));
        assertEquals(0, schema.at("/properties/diagnostics/maxItems").asInt(-1));
        assertTrue(schema.at("/properties/packageId/pattern").asText().contains("pkg-"));
    }

    private static boolean contains(JsonNode array, String value) {
        for (JsonNode item : array) if (value.equals(item.asText())) return true;
        return false;
    }

    private static JsonNode read(String name) throws Exception {
        try (InputStream input = V3JsonSchemaContractTest.class.getResourceAsStream("/contracts/v3/" + name)) {
            assertNotNull(input, "JSON Schema resource " + name);
            return JSON.readTree(input);
        }
    }
}
