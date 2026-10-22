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
    void stateUsesResourceOwnershipAndOperationSchemaFreezesSevenTypes() throws Exception {
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
        assertEquals(8, types.size());
        for (String expected : Arrays.asList("ADD_FILE", "REPLACE_MANAGED_FILE", "REPLACE_MANAGED_YAML", "UPSERT_MANAGED_CONFIG_BLOCK", "ENSURE_JAVA_ANNOTATION",
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

    @Test
    void v4PackageExcludesDigestAndConfigurationOperations() throws Exception {
        JsonNode schema = readV4("extension-update-package.schema.json");
        assertEquals("4", schema.at("/properties/protocolVersion/const").asText());
        assertTrue(contains(schema.path("required"), "expectedStateSequence"));
        assertFalse(schema.path("properties").has("currentStateDigest"));
        assertFalse(schema.path("properties").has("nextStateDigest"));
        assertEquals(1, schema.at("/$defs/payloadMetadata/required").size());
        assertFalse(schema.at("/$defs/payloadMetadata/properties").has("sha256"));
        JsonNode operation = readV4("operation.schema.json");
        assertFalse(contains(operation.at("/properties/type/enum"), "UPSERT_MANAGED_CONFIG_BLOCK"));
        assertTrue(contains(operation.at("/properties/type/enum"), "ENSURE_CAPABILITY_CONFIG"));
        assertTrue(operation.toString().contains("capabilityConfigParameters"));
        JsonNode nextState = readV4("template-state.schema.json");
        assertTrue(contains(nextState.path("required"), "stateSequence"));
        assertFalse(nextState.path("properties").has("managedConfiguration"));
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
    private static JsonNode readV4(String name) throws Exception {
        try (InputStream input = V3JsonSchemaContractTest.class.getResourceAsStream("/contracts/v4/" + name)) {
            assertNotNull(input, "JSON Schema resource " + name);
            return JSON.readTree(input);
        }
    }
}
