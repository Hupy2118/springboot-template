package com.devagentstudio.template.engine.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = EngineServiceApplication.class)
class NextEngineServiceIT {
    private static final ObjectMapper JSON = new ObjectMapper();
    @Autowired private WebApplicationContext context;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("devagentstudio.template-engine.source-root", () -> repositoryRoot().resolve("template-source").toString());
    }

    @Test
    void generateIsDeterministicAndIncludesDependencyClosureAndResourceOwners() throws Exception {
        MockMvc mvc = mvc();
        byte[] base = generate(mvc, capabilities());
        assertArrayEquals(base, generate(mvc, capabilities()));
        JsonNode baseState = zipJson(base, ".devagentstudio/template-state.json");
        assertEquals(6, baseState.size());
        assertEquals(3, baseState.path("schemaVersion").asInt());
        assertEquals(0, baseState.path("installedArtifacts").size());
        assertEquals(0, baseState.path("managedContributions").size());
        assertTrue(hasZipEntry(base, "frontend/src/extensions/providers.ts"));

        byte[] auth = generate(mvc, capabilities("authorization", true));
        assertArrayEquals(auth, generate(mvc, capabilities("authorization", true)));
        JsonNode authState = zipJson(auth, ".devagentstudio/template-state.json");
        assertTrue(authState.path("requested").has("authorization"));
        assertFalse(authState.path("requested").has("login"));
        assertTrue(authState.path("effective").has("login"));
        assertTrue(authState.path("effective").has("authorization"));
        assertTrue(hasZipEntry(auth, "frontend/src/pages/Login/index.tsx"));
        assertTrue(hasZipEntry(auth, "backend/migrations/001-schema.sql"));
        assertFalse(hasZipPrefix(auth, "backend/assembly/"));
        JsonNode maven = authState.path("managedContributions").path("maven:ZA21:bee-starter-auth");
        assertEquals("MAVEN_DEPENDENCY", maven.path("type").asText());
        assertEquals(Arrays.asList("login"), JSON.convertValue(maven.path("owners"), JSON.getTypeFactory().constructCollectionType(List.class, String.class)));
    }

    @Test
    void updateProducesOrderedDeterministicPackageThenSupportsNoChangeAndReconcile() throws Exception {
        MockMvc mvc = mvc();
        JsonNode baseState = zipJson(generate(mvc, capabilities()), ".devagentstudio/template-state.json");
        Map<String, Object> request = updateRequest(baseState, capabilities("authorization", true), "APPLY");
        byte[] first = postZip(mvc, "/v1/update-next", request, 200);
        byte[] second = postZip(mvc, "/v1/update-next", request, 200);
        assertArrayEquals(first, second);
        JsonNode metadata = zipJson(first, "extension-update-package.json");
        assertEquals("3", metadata.path("protocolVersion").asText());
        assertEquals("APPLY", metadata.path("mode").asText());
        assertTrue(metadata.path("operations").size() > 7);
        assertEquals(metadata.path("operations").size(), continuousIndexes(metadata.path("operations")));
        assertEquals(0, metadata.path("validationPlan").size());
        assertEquals(0, metadata.path("diagnostics").size());
        assertEquals(metadata.path("payloadManifest").size(), payloadEntryCount(first));
        assertEquals("ADD_FILE", metadata.path("operations").get(0).path("type").asText());
        assertEquals("ENSURE_MAVEN_DEPENDENCY", findOperation(metadata.path("operations"), "ENSURE_MAVEN_DEPENDENCY").path("type").asText());
        assertEquals("REPLACE_MANAGED_FILE", metadata.path("operations").get(metadata.path("operations").size() - 1).path("type").asText());

        String targetState = JSON.writeValueAsString(metadata.path("nextTemplateState"));
        mvc.perform(post("/v1/update-next").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(updateRequest(JSON.readTree(targetState), capabilities("authorization", true), "APPLY"))))
                .andExpect(status().isNoContent());

        byte[] reconcile = postZip(mvc, "/v1/update-next", updateRequest(JSON.readTree(targetState), capabilities("authorization", true), "RECONCILE"), 200);
        JsonNode repair = zipJson(reconcile, "extension-update-package.json");
        assertEquals(targetState, JSON.writeValueAsString(repair.path("nextTemplateState")));
        assertEquals(7, repair.path("operations").size());
        assertEquals(5, countOperation(repair.path("operations"), "REPLACE_MANAGED_FILE"));
        assertEquals(1, countOperation(repair.path("operations"), "ENSURE_JAVA_ANNOTATION"));
        assertEquals(1, countOperation(repair.path("operations"), "ENSURE_MAVEN_DEPENDENCY"));
    }

    @Test
    void updateMapsProtocolStateAndRemovalErrorsToFrozenCodes() throws Exception {
        MockMvc mvc = mvc();
        JsonNode state = zipJson(generate(mvc, capabilities("authorization", true)), ".devagentstudio/template-state.json");

        Map<String, Object> request = updateRequest(state, capabilities("authorization", true), "APPLY");
        request.put("protocolVersion", "2");
        assertError(mvc, request, 400, "PROTOCOL_VERSION_UNSUPPORTED");

        request = updateRequest(state, capabilities("authorization", true), "APPLY");
        Map<String, Object> stale = JSON.convertValue(state, Map.class);
        stale.put("schemaVersion", 2); request.put("currentTemplateState", stale);
        assertError(mvc, request, 400, "TEMPLATE_STATE_SCHEMA_UNSUPPORTED");

        request = updateRequest(state, capabilities("login", true), "APPLY");
        assertError(mvc, request, 409, "CAPABILITY_REMOVAL_UNSUPPORTED");

        request = updateRequest(state, capabilities("authorization", true), "APPLY");
        Map<String, Object> invalid = JSON.convertValue(state, Map.class);
        ((Map<String, Object>) invalid.get("managedContributions")).put("wrong-key", ((Map<String, Object>) invalid.get("managedContributions")).values().iterator().next());
        request.put("currentTemplateState", invalid);
        assertError(mvc, request, 400, "TEMPLATE_STATE_INVALID");
    }

    private MockMvc mvc() { return org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context).build(); }
    private byte[] generate(MockMvc mvc, Map<String, Object> capabilities) throws Exception {
        Map<String, Object> body = new LinkedHashMap<String, Object>(); body.put("requestedConfig", config(capabilities));
        return postZip(mvc, "/v1/generate-next", body, 200);
    }
    private byte[] postZip(MockMvc mvc, String path, Map<String, Object> body, int status) throws Exception {
        MvcResult result = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).accept("application/zip")
                .content(JSON.writeValueAsBytes(body))).andExpect(status().is(status)).andReturn();
        return result.getResponse().getContentAsByteArray();
    }
    private void assertError(MockMvc mvc, Map<String, Object> request, int status, String code) throws Exception {
        MvcResult result = mvc.perform(post("/v1/update-next").contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsBytes(request)))
                .andExpect(status().is(status)).andReturn();
        assertEquals(code, JSON.readTree(result.getResponse().getContentAsByteArray()).path("code").asText());
    }
    private Map<String, Object> updateRequest(JsonNode state, Map<String, Object> capabilities, String mode) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("protocolVersion", "3"); body.put("currentTemplateState", JSON.convertValue(state, Map.class));
        body.put("requestedConfig", config(capabilities)); body.put("mode", mode); return body;
    }
    private Map<String, Object> config(Map<String, Object> capabilities) {
        Map<String, Object> result = new LinkedHashMap<String, Object>(); result.put("capabilities", capabilities); return result;
    }
    private Map<String, Object> capabilities() { return new LinkedHashMap<String, Object>(); }
    private Map<String, Object> capabilities(String id, boolean enabled) {
        Map<String, Object> capability = new LinkedHashMap<String, Object>(); capability.put("enabled", enabled); capability.put("config", new LinkedHashMap<String, Object>());
        Map<String, Object> result = new LinkedHashMap<String, Object>(); result.put(id, capability); return result;
    }
    private JsonNode zipJson(byte[] zip, String path) throws Exception { return JSON.readTree(zipBytes(zip, path)); }
    private byte[] zipBytes(byte[] zip, String path) throws Exception {
        ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zip)); ZipEntry entry;
        while ((entry = input.getNextEntry()) != null) if (path.equals(entry.getName())) {
            ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count); return output.toByteArray();
        }
        throw new AssertionError("missing ZIP entry " + path);
    }
    private boolean hasZipEntry(byte[] zip, String path) throws Exception { for (String entry : zipEntries(zip)) if (entry.equals(path)) return true; return false; }
    private boolean hasZipPrefix(byte[] zip, String prefix) throws Exception { for (String entry : zipEntries(zip)) if (entry.startsWith(prefix)) return true; return false; }
    private List<String> zipEntries(byte[] zip) throws Exception {
        List<String> result = new ArrayList<String>(); ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zip)); ZipEntry entry;
        while ((entry = input.getNextEntry()) != null) result.add(entry.getName()); return result;
    }
    private int payloadEntryCount(byte[] zip) throws Exception { int result = 0; for (String entry : zipEntries(zip)) if (entry.startsWith("payload/")) result++; return result; }
    private int continuousIndexes(JsonNode operations) { for (int i = 0; i < operations.size(); i++) assertEquals(i, operations.get(i).path("index").asInt(-1)); return operations.size(); }
    private int countOperation(JsonNode operations, String type) { int result = 0; for (JsonNode item : operations) if (type.equals(item.path("type").asText())) result++; return result; }
    private JsonNode findOperation(JsonNode operations, String type) { for (JsonNode item : operations) if (type.equals(item.path("type").asText())) return item; throw new AssertionError("operation missing " + type); }
    private static Path repositoryRoot() {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null && !Files.isDirectory(current.resolve("template-source"))) current = current.getParent();
        if (current == null) throw new AssertionError("repository root not found");
        return current;
    }
}
