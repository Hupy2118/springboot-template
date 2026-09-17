package com.xcodeagent.template.engine.service;

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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = EngineServiceApplication.class)
class EngineServiceIT {
    private static final ObjectMapper JSON = new ObjectMapper();
    @Autowired private WebApplicationContext context;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("xcodeagent.template-engine.source-root", () -> repositoryRoot().resolve("template-source").toString());
    }

    @Test
    void localProfileBindsOnlyToIpv4Loopback() throws Exception {
        String localProfile = new String(Files.readAllBytes(repositoryRoot()
                .resolve("template-engine/engine-service/config/application-local.yml")), StandardCharsets.UTF_8);
        assertTrue(localProfile.contains("address: 127.0.0.1"));
        assertTrue(!localProfile.contains("address: 0.0.0.0"));
    }

    @Test
    void planGenerateUpdateWithoutAuthenticationAreStateless() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
        String requestedAuthorization = "{\"capabilities\":{\"authorization\":{\"enabled\":true,\"config\":{}}}}";
        byte[] generated = mvc.perform(post("/v1/generate").contentType(MediaType.APPLICATION_JSON).content("{\"requestedConfig\":" + requestedAuthorization + "}"))
                .andExpect(status().isOk()).andExpect(content().contentType("application/zip")).andReturn().getResponse().getContentAsByteArray();
        JsonNode generatedState = zipJson(generated, ".xcodeagent/template-state.json");
        assertEquals(2, generatedState.path("schemaVersion").asInt());
        assertTrue(!generatedState.has("managedFiles"));
        assertTrue(generatedState.path("appliedAdditions").has("authorization.role-page"));
        assertTrue(zipText(generated, "backend/docs/auth/sql/ddl.sql").contains("CREATE TABLE `role`"));
        assertTrue(!hasZipEntry(generated, "backend/docs/auth/sql/initialization.sql"));
        assertTrue(!hasZipPrefix(generated, "frontend/node_modules/"));
        assertEquals(readTemplateContract("route-projector.json"),
                zipText(generated, ".xcodeagent/template-contracts/route-projector.json"));
        assertEquals(readTemplateContract("route-projector-input.schema.json"),
                zipText(generated, ".xcodeagent/template-contracts/route-projector-input.schema.json"));
        assertEquals(readTemplateContract("route-projector-output.schema.json"),
                zipText(generated, ".xcodeagent/template-contracts/route-projector-output.schema.json"));

        String update = "{\"protocolVersion\":\"2\",\"currentTemplateState\":" + generatedState + ",\"requestedConfig\":" + requestedAuthorization + ",\"mode\":\"RECONCILE\"}";
        byte[] changed = mvc.perform(post("/v1/update").contentType(MediaType.APPLICATION_JSON).content(update))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        JsonNode updatePackage = zipJson(changed, "strategy-update-package.json");
        JsonNode nextState = updatePackage.path("nextTemplateState");
        assertEquals("2", updatePackage.path("protocolVersion").asText());
        assertTrue(updatePackage.path("strategies").size() > 0);
        assertTrue(updatePackage.path("payloadManifest").has("payload/backend/docs/auth/sql/ddl.sql"));
        assertEquals(updatePackage.path("strategies").size(), continuousIndexes(updatePackage.path("strategies")));
        for (JsonNode validation : updatePackage.path("validationPlan")) {
            assertTrue(validation.hasNonNull("validationId"));
            assertTrue(!validation.has("validatorId"));
            assertTrue(!validation.has("parameters"));
            assertTrue(validation.has("executionMode") && validation.has("blocking")
                    && validation.has("timeoutSeconds") && validation.has("workingDirectory"));
        }
        for (JsonNode strategy : updatePackage.path("strategies")) {
            if ("ADD_FILE".equals(strategy.path("type").asText())) {
                assertEquals(0, strategy.path("parameters").size());
                assertTrue(updatePackage.path("payloadManifest").has(strategy.path("payloadRef").asText()));
            } else assertTrue(strategy.path("payloadRef").isNull());
        }
        for (String entry : zipEntries(changed))
            assertTrue("strategy-update-package.json".equals(entry) || entry.startsWith("payload/"), entry);
        byte[] repeated = mvc.perform(post("/v1/update").contentType(MediaType.APPLICATION_JSON).content(update))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(changed, repeated);
        assertTrue(nextState.path("effective").has("login"));
        assertTrue(nextState.path("effective").has("authorization"));

        String noChange = "{\"protocolVersion\":\"2\",\"currentTemplateState\":" + nextState + ",\"requestedConfig\":" + requestedAuthorization + ",\"mode\":\"APPLY\"}";
        mvc.perform(post("/v1/update").contentType(MediaType.APPLICATION_JSON).content(noChange)).andExpect(status().isNoContent());
    }

    @Test
    void generateGoldenIsDeterministicAndMatchesUpdateAtomicSurfaceApplication() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
        String empty = "{\"capabilities\":{}}";
        String login = "{\"capabilities\":{\"login\":{\"enabled\":true,\"config\":{}}}}";
        String authorization = "{\"capabilities\":{\"authorization\":{\"enabled\":true,\"config\":{}}}}";
        String both = "{\"capabilities\":{\"authorization\":{\"enabled\":true,\"config\":{}},\"login\":{\"enabled\":true,\"config\":{}}}}";
        for (String requested : Arrays.asList(empty, login, authorization, both)) {
            byte[] first = generate(mvc, requested);
            assertArrayEquals(first, generate(mvc, requested));
            JsonNode state = zipJson(first, ".xcodeagent/template-state.json");
            assertEquals(5, state.size());
            assertEquals(2, state.path("schemaVersion").asInt());
            assertTrue(state.has("templateRevision") && state.has("requested") && state.has("effective") && state.has("appliedAdditions"));
        }

        byte[] pristine = generate(mvc, empty);
        JsonNode pristineState = zipJson(pristine, ".xcodeagent/template-state.json");
        byte[] generated = generate(mvc, authorization);
        String update = "{\"protocolVersion\":\"2\",\"currentTemplateState\":" + pristineState
                + ",\"requestedConfig\":" + authorization + ",\"mode\":\"APPLY\"}";
        byte[] updateZip = mvc.perform(post("/v1/update")
                        .contentType(MediaType.APPLICATION_JSON).content(update))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        JsonNode updatePackage = zipJson(updateZip, "strategy-update-package.json");
        Map<String, String> applied = new LinkedHashMap<String, String>();
        for (String path : surfacePaths()) applied.put(path, zipText(pristine, path));
        for (JsonNode strategy : updatePackage.path("strategies")) applySurfaceStrategy(applied, strategy);
        for (String path : surfacePaths()) assertEquals(zipText(generated, path), applied.get(path), path);
        assertTrue(zipText(generated, "frontend/src/capability-extensions/menuTransforms.ts").contains("useAuthorizationMenuTransform(current)"));
        assertTrue(zipText(generated, "backend/src/main/java/com/cmbchina/backend/common/config/CapabilityWebMvcConfiguration.java")
                .contains("ResourcePermissionInterceptor.class"));
    }

    private static JsonNode zipJson(byte[] zip, String path) throws Exception {
        return JSON.readTree(zipBytes(zip, path));
    }
    private static String readTemplateContract(String file) throws Exception {
        return new String(Files.readAllBytes(repositoryRoot().resolve("template-source/base/contracts").resolve(file)), StandardCharsets.UTF_8);
    }
    private static byte[] generate(MockMvc mvc, String requested) throws Exception {
        return mvc.perform(post("/v1/generate")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"requestedConfig\":" + requested + "}"))
                .andExpect(status().isOk()).andExpect(content().contentType("application/zip"))
                .andReturn().getResponse().getContentAsByteArray();
    }
    private static List<String> surfacePaths() {
        return Arrays.asList("frontend/src/capability-extensions/providers.tsx", "frontend/src/capability-extensions/routes.tsx",
                "frontend/src/capability-extensions/routeGuards.tsx", "frontend/src/capability-extensions/menuTransforms.ts",
                "backend/src/main/java/com/cmbchina/backend/common/config/CapabilityWebMvcConfiguration.java");
    }
    private static void applySurfaceStrategy(Map<String, String> files, JsonNode strategy) {
        String target = strategy.path("target").asText();
        String source = files.get(target);
        if (source == null) return;
        String type = strategy.path("type").asText();
        JsonNode parameters = strategy.path("parameters");
        if ("ENSURE_IMPORT".equals(type)) {
            String statement = parameters.path("importStatement").asText();
            if (!source.contains(statement)) {
                int last = source.lastIndexOf("import ");
                int end = source.indexOf('\n', last);
                files.put(target, source.substring(0, end + 1) + statement + "\n" + source.substring(end + 1));
            }
        } else if ("TEXT_ANCHOR_INSERT".equals(type)) {
            String anchor = parameters.path("anchor").asText();
            String content = parameters.path("content").asText();
            int index = source.indexOf(anchor);
            if (index < 0 || index != source.lastIndexOf(anchor)) throw new AssertionError("invalid Golden anchor " + target);
            if ("before".equals(parameters.path("position").asText())) {
                int newline = source.lastIndexOf('\n', index - 1);
                index = newline < 0 ? 0 : newline + 1;
            }
            files.put(target, source.substring(0, index) + content + source.substring(index));
        }
    }
    private static int continuousIndexes(JsonNode strategies) {
        int index = 0;
        for (JsonNode strategy : strategies) {
            if (strategy.path("index").asInt(-1) != index) throw new AssertionError("strategy index is not continuous");
            index++;
        }
        return index;
    }
    private static List<String> zipEntries(byte[] zip) throws Exception {
        List<String> entries = new ArrayList<String>();
        ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zip));
        java.util.zip.ZipEntry entry;
        while ((entry = input.getNextEntry()) != null) entries.add(entry.getName());
        return entries;
    }
    private static String zipText(byte[] zip, String path) throws Exception {
        return new String(zipBytes(zip, path), StandardCharsets.UTF_8);
    }
    private static byte[] zipBytes(byte[] zip, String path) throws Exception {
        ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zip));
        java.util.zip.ZipEntry entry;
        while ((entry = input.getNextEntry()) != null) if (path.equals(entry.getName())) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
        throw new AssertionError("missing ZIP entry " + path);
    }
    private static boolean hasZipEntry(byte[] zip, String path) throws Exception {
        ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zip));
        java.util.zip.ZipEntry entry;
        while ((entry = input.getNextEntry()) != null) if (path.equals(entry.getName())) return true;
        return false;
    }
    private static boolean hasZipPrefix(byte[] zip, String prefix) throws Exception {
        ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zip));
        java.util.zip.ZipEntry entry;
        while ((entry = input.getNextEntry()) != null) if (entry.getName().startsWith(prefix)) return true;
        return false;
    }
    private static Path repositoryRoot() {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null && !Files.isDirectory(current.resolve("template-source"))) current = current.getParent();
        assertNotNull(current, "repository root"); return current;
    }
}
