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

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        registry.add("xcodeagent.template-engine.principals[0].principal-id", () -> "test-full");
        registry.add("xcodeagent.template-engine.principals[0].principal-type", () -> "XCODE_AGENT");
        registry.add("xcodeagent.template-engine.principals[0].token-sha256", () -> "f807843a7ee53c652d63a1d2215e104ab2f265ed0d2bea0cf4c64f1486764593");
        registry.add("xcodeagent.template-engine.principals[0].scopes[0]", () -> "template.plan");
        registry.add("xcodeagent.template-engine.principals[0].scopes[1]", () -> "template.generate");
        registry.add("xcodeagent.template-engine.principals[0].scopes[2]", () -> "template.update");
        registry.add("xcodeagent.template-engine.principals[1].principal-id", () -> "test-plan");
        registry.add("xcodeagent.template-engine.principals[1].principal-type", () -> "XCODE_AGENT");
        registry.add("xcodeagent.template-engine.principals[1].token-sha256", () -> "3a1af87bdeb7471c0124f17aa27e90ad46bbbee710dc7cad2c3f6dbd2feea646");
        registry.add("xcodeagent.template-engine.principals[1].scopes[0]", () -> "template.plan");
    }

    @Test
    void planGenerateUpdateAndAuthenticationAreStateless() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
        String requestedAuthorization = "{\"capabilities\":{\"authorization\":{\"enabled\":true,\"config\":{}}}}";
        byte[] generated = mvc.perform(post("/v1/generate").header("Authorization", "Bearer stage3-demo-token").contentType(MediaType.APPLICATION_JSON).content("{\"requestedConfig\":" + requestedAuthorization + "}"))
                .andExpect(status().isOk()).andExpect(content().contentType("application/zip")).andReturn().getResponse().getContentAsByteArray();
        JsonNode generatedState = zipJson(generated, ".xcodeagent/template-state.json");
        assertEquals(2, generatedState.path("schemaVersion").asInt());
        assertTrue(!generatedState.has("managedFiles"));
        assertTrue(generatedState.path("appliedAdditions").has("authorization.role-page"));

        String update = "{\"currentTemplateState\":" + generatedState + ",\"requestedConfig\":" + requestedAuthorization + ",\"mode\":\"RECONCILE\"}";
        byte[] changed = mvc.perform(post("/v1/update").header("Authorization", "Bearer stage3-demo-token").contentType(MediaType.APPLICATION_JSON).content(update))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        JsonNode nextState = zipJson(changed, "next-template-state.json");
        assertTrue(zipJson(changed, "modification-strategy.json").size() > 0);
        assertTrue(nextState.path("effective").has("login"));
        assertTrue(nextState.path("effective").has("authorization"));

        String noChange = "{\"currentTemplateState\":" + nextState + ",\"requestedConfig\":" + requestedAuthorization + ",\"mode\":\"APPLY\"}";
        mvc.perform(post("/v1/update").header("Authorization", "Bearer stage3-demo-token").contentType(MediaType.APPLICATION_JSON).content(noChange)).andExpect(status().isNoContent());
        mvc.perform(post("/v1/generate").contentType(MediaType.APPLICATION_JSON).content("{\"requestedConfig\":" + requestedAuthorization + "}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/generate").header("Authorization", "Bearer stage3-plan-token").contentType(MediaType.APPLICATION_JSON).content("{\"requestedConfig\":" + requestedAuthorization + "}")).andExpect(status().isForbidden());
    }

    private static JsonNode zipJson(byte[] zip, String path) throws Exception {
        ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zip));
        java.util.zip.ZipEntry entry;
        while ((entry = input.getNextEntry()) != null) if (path.equals(entry.getName())) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return JSON.readTree(output.toByteArray());
        }
        throw new AssertionError("missing ZIP entry " + path);
    }
    private static Path repositoryRoot() {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null && !Files.isDirectory(current.resolve("template-source"))) current = current.getParent();
        assertNotNull(current, "repository root"); return current;
    }
}
