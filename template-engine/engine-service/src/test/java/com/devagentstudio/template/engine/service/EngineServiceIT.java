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
class EngineServiceIT {
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
        assertEquals(7, baseState.size());
        assertEquals(3, baseState.path("schemaVersion").asInt());
        assertEquals(0, baseState.path("installedArtifacts").size());
        assertEquals(0, baseState.path("managedContributions").size());
        assertTrue(hasZipEntry(base, "frontend/src/extensions/providers.ts"));
        assertFalse(hasZipEntry(base, "frontend/src/config/app.generated.ts"));

        byte[] auth = generate(mvc, capabilities("authorization", true));
        assertArrayEquals(auth, generate(mvc, capabilities("authorization", true)));
        JsonNode authState = zipJson(auth, ".devagentstudio/template-state.json");
        assertTrue(authState.path("requested").has("authorization"));
        assertFalse(authState.path("requested").has("login"));
        assertTrue(authState.path("effective").has("login"));
        assertTrue(authState.path("effective").has("authorization"));
        assertTrue(hasZipEntry(auth, "frontend/src/pages/Login/index.tsx"));
        for (String asset : authorizationAssets()) {
            assertTrue(hasZipEntry(auth, asset), asset);
            assertFalse(hasZipEntry(base, asset), asset);
            assertFalse(hasZipEntry(generate(mvc, capabilities("login", true)), asset), asset);
        }
        assertFalse(hasZipEntry(auth, "backend/migrations/001-schema.sql"));
        assertFalse(hasZipEntry(auth, "backend/docs/auth/sql/ddl.sql"));
        assertFalse(hasZipPrefix(auth, "backend/assembly/"));
        JsonNode maven = authState.path("managedContributions").path("maven:ZA21:bee-starter-auth");
        assertEquals("MAVEN_DEPENDENCY", maven.path("type").asText());
        assertEquals(Arrays.asList("login"), JSON.convertValue(maven.path("owners"), JSON.getTypeFactory().constructCollectionType(List.class, String.class)));
    }

    @Test
    void applicationConfigurationIsKeptSeparateFromCapabilityResolution() throws Exception {
        MockMvc mvc = mvc();
        Map<String, Object> requestedConfig = config(capabilities("login", true));
        requestedConfig.put("applicationConfig", applicationConfig("人员管理"));
        Map<String, Object> generateRequest = new LinkedHashMap<String, Object>();
        generateRequest.put("requestedConfig", requestedConfig);

        byte[] generated = postZip(mvc, "/v1/generate", generateRequest, 200);
        JsonNode state = zipJson(generated, ".devagentstudio/template-state.json");
        assertFalse(state.has("managedConfiguration"));
        assertFalse(state.toString().contains("111"));
        assertTrue(hasZipEntry(generated, "frontend/.env"));
        String backendYaml = new String(zipBytes(generated, "backend/src/main/resources/application.yml"), StandardCharsets.UTF_8);
        String backendDevYaml = new String(zipBytes(generated, "backend/src/main/resources/application-dev.yml"), StandardCharsets.UTF_8);
        assertTrue(backendDevYaml.contains("private-key: '111'"));
        assertFalse(backendYaml.contains("private-key: '111'"));
        assertFalse(backendYaml.matches("(?sm).*^auth:.*"));
        String frontendEnv = new String(zipBytes(generated, "frontend/.env"), StandardCharsets.UTF_8);
        assertTrue(frontendEnv.contains("PUBLIC_APP_NAME=\"人员管理\""));
        assertTrue(frontendEnv.contains("PUBLIC_YHT_CLIENT_ID=\"000\""));
        assertTrue(frontendEnv.contains("PUBLIC_YHT_AUTHORIZE_URI=\"https://one-account-gateway.paasuat.cmbchina.cn/auth-server/auth\""));
        assertFalse(frontendEnv.contains("111"));

        Map<String, Object> updateRequest = updateRequest(state, capabilities("login", true), "APPLY");
        updateRequest.put("protocolVersion", "4");
        mvc.perform(post("/v1/update").contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsBytes(updateRequest)))
                .andExpect(status().isNoContent());

        Map<String, Object> invalidConfig = applicationConfig("人员管理");
        ((Map<String, Object>) ((Map<String, Object>) invalidConfig.get("auth")).get("yht")).remove("privateKey");
        Map<String, Object> invalidRequested = config(capabilities("login", true));
        invalidRequested.put("applicationConfig", invalidConfig);
        Map<String, Object> invalidGenerate = new LinkedHashMap<String, Object>();
        invalidGenerate.put("requestedConfig", invalidRequested);
        mvc.perform(post("/v1/generate").contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsBytes(invalidGenerate)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void generateProjectsLoginAndAuthorizationLeavesToDevYamlAndFrontendEnv() throws Exception {
        MockMvc mvc = mvc();
        Map<String, Object> application = applicationConfig("人员管理");
        @SuppressWarnings("unchecked") Map<String, Object> yht = (Map<String, Object>) ((Map<String, Object>) application.get("auth")).get("yht");
        yht.put("centerUri", "https://accounts.example.test/center");
        yht.put("authorizeUri", "https://accounts.example.test/authorize");
        yht.put("redirectUri", "https://app.example.test/login");
        yht.put("endUrl", "https://accounts.example.test/end");
        yht.put("logoutShareJs", "https://accounts.example.test/logout.js");
        Map<String, Object> api = new LinkedHashMap<String, Object>(); api.put("baseUrl", "https://api.example.test"); api.put("viewId", "view-1");
        Map<String, Object> authorization = new LinkedHashMap<String, Object>(); authorization.put("api", api); application.put("authorization", authorization);
        Map<String, Object> requested = config(capabilities("authorization", true)); requested.put("applicationConfig", application);
        Map<String, Object> body = new LinkedHashMap<String, Object>(); body.put("requestedConfig", requested);
        byte[] generated = postZip(mvc, "/v1/generate", body, 200);
        String applicationYaml = new String(zipBytes(generated, "backend/src/main/resources/application.yml"), StandardCharsets.UTF_8);
        String devYaml = new String(zipBytes(generated, "backend/src/main/resources/application-dev.yml"), StandardCharsets.UTF_8);
        String env = new String(zipBytes(generated, "frontend/.env"), StandardCharsets.UTF_8);
        assertTrue(applicationYaml.contains("${yht.authentication.authorize-uri}"));
        assertFalse(applicationYaml.matches("(?sm).*^auth:.*"));
        assertTrue(devYaml.contains("authorize-uri: https://accounts.example.test/authorize"));
        assertTrue(devYaml.contains("base-url: https://api.example.test"));
        assertTrue(devYaml.contains("view-id: view-1"));
        assertTrue(applicationYaml.contains("user-query-path:") && applicationYaml.contains("/v2/users/query"));
        assertTrue(env.contains("PUBLIC_YHT_AUTHORIZE_URI=\"https://accounts.example.test/authorize\""));
        assertTrue(env.contains("PUBLIC_YHT_END_URL=\"https://accounts.example.test/end\""));
        assertTrue(env.contains("PUBLIC_YHT_LOGOUT_SHARE_JS=\"https://accounts.example.test/logout.js\""));
        assertFalse(zipJson(generated, ".devagentstudio/template-state.json").toString().contains("111"));
    }

    @Test
    void updateProducesOrderedDeterministicPackageThenSupportsNoChangeAndReconcile() throws Exception {
        MockMvc mvc = mvc();
        JsonNode baseState = zipJson(generate(mvc, capabilities()), ".devagentstudio/template-state.json");
        Map<String, Object> request = updateRequest(baseState, capabilities("authorization", true), "APPLY");
        byte[] first = postZip(mvc, "/v1/update", request, 200);
        byte[] second = postZip(mvc, "/v1/update", request, 200);
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
        mvc.perform(post("/v1/update").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(updateRequest(JSON.readTree(targetState), capabilities("authorization", true), "APPLY"))))
                .andExpect(status().isNoContent());

        byte[] reconcile = postZip(mvc, "/v1/update", updateRequest(JSON.readTree(targetState), capabilities("authorization", true), "RECONCILE"), 200);
        JsonNode repair = zipJson(reconcile, "extension-update-package.json");
        assertEquals(JSON.readTree(targetState), repair.path("nextTemplateState"));
        assertEquals(10, repair.path("operations").size());
        assertEquals(5, countOperation(repair.path("operations"), "REPLACE_MANAGED_FILE"));
        assertEquals(1, countOperation(repair.path("operations"), "ENSURE_JAVA_ANNOTATION"));
        assertEquals(2, countOperation(repair.path("operations"), "ENSURE_MAVEN_DEPENDENCY"));
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

    @Test
    void v4InstallsCapabilitiesWithoutConfigurationOrDigests() throws Exception {
        MockMvc mvc = mvc();
        JsonNode base = zipJson(generate(mvc, capabilities()), ".devagentstudio/template-state.json");
        Map<String, Object> request = updateRequest(base, capabilities("authorization", true), "APPLY");
        request.put("protocolVersion", "4");
        JsonNode packageInfo = zipJson(postZip(mvc, "/v1/update", request, 200), "extension-update-package.json");
        assertAuthorizationAdds(packageInfo);
        assertEquals("4", packageInfo.path("protocolVersion").asText());
        assertEquals(base.path("templateRevision").asText(), packageInfo.path("sourceRevision").asText());
        assertEquals(0, packageInfo.path("expectedStateSequence").asInt());
        assertEquals(1, packageInfo.path("nextTemplateState").path("stateSequence").asInt());
        assertFalse(packageInfo.has("currentStateDigest"));
        assertFalse(packageInfo.has("nextStateDigest"));
        assertFalse(packageInfo.path("nextTemplateState").has("managedConfiguration"));
        for (JsonNode payload : packageInfo.path("payloadManifest")) assertEquals(1, payload.size());
        for (JsonNode operation : packageInfo.path("operations"))
            assertFalse("UPSERT_MANAGED_CONFIG_BLOCK".equals(operation.path("type").asText()));
        assertEquals(3, countOperation(packageInfo.path("operations"), "ENSURE_CAPABILITY_CONFIG"));
        for (JsonNode operation : packageInfo.path("operations")) if ("ENSURE_CAPABILITY_CONFIG".equals(operation.path("type").asText())) {
            assertTrue(operation.path("payloadRef").asText().startsWith("payload/"));
            assertTrue(operation.path("parameters").path("capabilityIds").size() > 0);
        }
        byte[] fullProject = generate(mvc, capabilities("authorization", true));
        byte[] updateZip = postZip(mvc, "/v1/update", request, 200);
        for (JsonNode operation : packageInfo.path("operations")) {
            String type = operation.path("type").asText();
            if ("ADD_FILE".equals(type) || "REPLACE_MANAGED_FILE".equals(type)) {
                String target = operation.path("target").asText();
                assertArrayEquals(zipBytes(fullProject, target), zipBytes(updateZip, operation.path("payloadRef").asText()), target);
            }
        }

        Map<String, Object> repeated = updateRequest(packageInfo.path("nextTemplateState"), capabilities("authorization", true), "APPLY");
        repeated.put("protocolVersion", "4");
        mvc.perform(post("/v1/update").contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsBytes(repeated)))
                .andExpect(status().isNoContent());
        ((Map<String, Object>) request.get("requestedConfig")).put("applicationConfig", applicationConfig("blocked"));
        assertError(mvc, request, 400, "BAD_REQUEST");

        Map<String, Object> oldState = JSON.convertValue(base, Map.class);
        oldState.remove("stateSequence");
        oldState.put("managedConfiguration", applicationConfig("legacy"));
        Map<String, Object> legacy = updateRequest(JSON.valueToTree(oldState), capabilities("login", true), "APPLY");
        legacy.put("protocolVersion", "4");
        JsonNode migrated = zipJson(postZip(mvc, "/v1/update", legacy, 200), "extension-update-package.json");
        assertEquals(1, migrated.path("nextTemplateState").path("stateSequence").asInt());
        assertFalse(migrated.path("nextTemplateState").has("managedConfiguration"));

        // Regression: an already applied login V4 package (sequence 1) must be a
        // valid starting point for adding authorization in the next V4 package.
        Map<String, Object> installLogin = updateRequest(base, capabilities("login", true), "APPLY");
        installLogin.put("protocolVersion", "4");
        JsonNode loginPackage = zipJson(postZip(mvc, "/v1/update", installLogin, 200), "extension-update-package.json");
        JsonNode loginState = loginPackage.path("nextTemplateState");
        assertEquals("2026.10.23.2", loginPackage.path("sourceRevision").asText());
        assertEquals(0, loginPackage.path("expectedStateSequence").asInt());
        assertEquals(1, loginState.path("stateSequence").asInt());
        assertEquals(1, loginState.path("effective").size());
        assertTrue(loginState.path("effective").has("login"));

        Map<String, Object> loginAndAuthorization = capabilities("login", true);
        loginAndAuthorization.putAll(capabilities("authorization", true));
        Map<String, Object> addAuthorization = updateRequest(loginState, loginAndAuthorization, "APPLY");
        addAuthorization.put("protocolVersion", "4");
        byte[] authorizationZip = postZip(mvc, "/v1/update", addAuthorization, 200);
        JsonNode authorization = zipJson(authorizationZip, "extension-update-package.json");
        assertAuthorizationAdds(authorization);
        for (JsonNode operation : authorization.path("operations")) {
            if ("backend/sql/authorization/schema.sql".equals(operation.path("target").asText()))
                assertArrayEquals(zipBytes(fullProject, operation.path("target").asText()),
                        zipBytes(authorizationZip, operation.path("payloadRef").asText()));
        }
        Map<String, Object> repeatedAuthorization = updateRequest(authorization.path("nextTemplateState"), loginAndAuthorization, "APPLY");
        repeatedAuthorization.put("protocolVersion", "4");
        postZip(mvc, "/v1/update", repeatedAuthorization, 204);
        assertEquals(2, countOperation(authorization.path("operations"), "ENSURE_CAPABILITY_CONFIG"));
        assertEquals("2026.10.23.2", authorization.path("sourceRevision").asText());
        assertEquals(1, authorization.path("expectedStateSequence").asInt());
        assertEquals(2, authorization.path("nextTemplateState").path("stateSequence").asInt());
        assertEquals(2, authorization.path("nextTemplateState").path("effective").size());
        assertTrue(authorization.path("nextTemplateState").path("effective").has("login"));
        assertTrue(authorization.path("nextTemplateState").path("effective").has("authorization"));
        for (JsonNode operation : authorization.path("operations")) {
            if ("ADD_FILE".equals(operation.path("type").asText()))
                assertTrue(operation.path("operationId").asText().contains("authorization"));
        }
    }

    private static List<String> authorizationAssets() {
        return Arrays.asList("backend/scripts/bootstrap-authorization.sh", "backend/scripts/bootstrap-authorization.ps1",
                "backend/sql/authorization/schema.sql",
                "backend/src/main/java/com/cmbchina/backend/auth/bootstrap/AuthorizationBootstrapCommand.java",
                "backend/src/main/java/com/cmbchina/backend/auth/bootstrap/BootstrapDataSource.java",
                "backend/src/main/java/com/cmbchina/backend/common/config/WebMvcConfiguration.java");
    }
    private void assertAuthorizationAdds(JsonNode info) {
        assertTrue(info.path("nextTemplateState").path("installedArtifacts")
                .has("backend:authorization:sql/authorization/schema.sql"));
        for (JsonNode operation : info.path("operations")) {
            assertFalse("backend/docs/auth/sql/ddl.sql".equals(operation.path("target").asText()));
            assertFalse("backend/migrations/001-schema.sql".equals(operation.path("target").asText()));
        }
        for (String asset : authorizationAssets()) {
            int found = 0;
            for (JsonNode operation : info.path("operations")) if (asset.equals(operation.path("target").asText())) {
                assertEquals("ADD_FILE", operation.path("type").asText());
                assertTrue(operation.path("payloadRef").asText().startsWith("payload/"));
                found++;
            }
            assertEquals(1, found, asset);
        }
    }

    private MockMvc mvc() { return org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context).build(); }
    private byte[] generate(MockMvc mvc, Map<String, Object> capabilities) throws Exception {
        Map<String, Object> body = new LinkedHashMap<String, Object>(); body.put("requestedConfig", config(capabilities));
        return postZip(mvc, "/v1/generate", body, 200);
    }
    private byte[] postZip(MockMvc mvc, String path, Map<String, Object> body, int status) throws Exception {
        MvcResult result = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).accept("application/zip")
                .content(JSON.writeValueAsBytes(body))).andExpect(status().is(status)).andReturn();
        return result.getResponse().getContentAsByteArray();
    }
    private void assertError(MockMvc mvc, Map<String, Object> request, int status, String code) throws Exception {
        MvcResult result = mvc.perform(post("/v1/update").contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsBytes(request)))
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
    private Map<String, Object> applicationConfig(String appName) {
        Map<String, Object> theme = new LinkedHashMap<String, Object>(); theme.put("primaryColor", "#6b3cf0");
        Map<String, Object> yht = new LinkedHashMap<String, Object>();
        yht.put("clientId", "000"); yht.put("privateKey", "111"); yht.put("publicKey", "123"); yht.put("centerPublicKey", "456");
        Map<String, Object> auth = new LinkedHashMap<String, Object>();
        auth.put("enable", true); auth.put("authnSource", "yst"); auth.put("yht", yht);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("appName", appName); result.put("appIcon", "AppstoreOutlined"); result.put("theme", theme); result.put("auth", auth);
        return result;
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
