package com.devagentstudio.template.engine.service;

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
import org.springframework.web.context.WebApplicationContext;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = EngineServiceApplication.class)
class V3AssemblyParityIT {
    private static final ObjectMapper JSON = new ObjectMapper();
    @Autowired private WebApplicationContext context;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("devagentstudio.template-engine.source-root", () -> repositoryRoot().resolve("template-source").toString());
    }

    @Test
    void frontendAndBackendProjectsMatchMaintenanceAssembliesForEveryProfile() throws Exception {
        Path repository = repositoryRoot();
        Path frontendRoot = repository.resolve("template-source/code/frontend");
        Path temporaryBackend = Files.createTempDirectory("v3-backend-parity-");
        copyDirectory(repository.resolve("template-source/code/backend/base"), temporaryBackend.resolve("base"));
        copyDirectory(repository.resolve("template-source/code/backend/extensions"), temporaryBackend.resolve("extensions"));
        copyDirectory(repository.resolve("template-source/code/backend/assembly"), temporaryBackend.resolve("assembly"));
        try {
            for (String profile : Arrays.asList("base", "login", "authorization", "full")) {
                byte[] generated = generate(mvc(), requested(profile));
                Map<String, byte[]> generatedFrontend = zipFiles(generated, "frontend/");
                Map<String, byte[]> generatedBackend = zipFiles(generated, "backend/");
                String extensions = "base".equals(profile) ? "" : "login".equals(profile) ? "login" : "authorization".equals(profile) ? "authorization" : "login,authorization";

                Path frontendOutput = Files.createTempDirectory(frontendRoot, ".v3-parity-");
                try {
                    run(new ProcessBuilder("node", frontendRoot.resolve("assembly/assemble.mjs").toString(),
                            "--extensions=" + extensions, "--output=" + frontendRoot.relativize(frontendOutput).toString())
                            .directory(frontendRoot.toFile()));
                    Map<String, byte[]> assembled = readFiles(frontendOutput);
                    assembled.remove(".devagentstudio-template-generated.json");
                    assertByteMapsEqual(assembled, generatedFrontend, "frontend " + profile);
                } finally { deleteTree(frontendOutput); }

                run(new ProcessBuilder("mvn", "-q", "-f", temporaryBackend.resolve("assembly/pom.xml").toString(),
                        "compile", "exec:java", "-Dexec.mainClass=com.cmbchina.template.backend.maintenance.BackendTemplateCli",
                        "-Dexec.args=reset " + profile).directory(temporaryBackend.toFile()));
                Map<String, byte[]> assembledBackend = readFiles(temporaryBackend.resolve("workspace"));
                assembledBackend.remove(".xcodeagent/backend-workspace-state.json");
                assertByteMapsEqual(assembledBackend, generatedBackend, "backend " + profile);
            }
        } finally { deleteTree(temporaryBackend); }
    }

    private byte[] generate(MockMvc mvc, Map<String, Object> capabilities) throws Exception {
        Map<String, Object> config = new LinkedHashMap<String, Object>(); config.put("capabilities", capabilities);
        Map<String, Object> request = new LinkedHashMap<String, Object>(); request.put("requestedConfig", config);
        return mvc.perform(post("/v1/generate-next").contentType(MediaType.APPLICATION_JSON).accept("application/zip")
                        .content(JSON.writeValueAsBytes(request)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    }
    private Map<String, Object> requested(String profile) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if ("login".equals(profile) || "full".equals(profile)) result.put("login", capability());
        if ("authorization".equals(profile) || "full".equals(profile)) result.put("authorization", capability());
        return result;
    }
    private Map<String, Object> capability() { Map<String, Object> value = new LinkedHashMap<String, Object>(); value.put("enabled", Boolean.TRUE); value.put("config", new LinkedHashMap<String, Object>()); return value; }
    private Map<String, byte[]> zipFiles(byte[] zip, String prefix) throws Exception {
        Map<String, byte[]> result = new LinkedHashMap<String, byte[]>(); ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zip)); ZipEntry entry;
        while ((entry = input.getNextEntry()) != null) if (entry.getName().startsWith(prefix)) result.put(entry.getName().substring(prefix.length()), readAll(input));
        return result;
    }
    private Map<String, byte[]> readFiles(Path root) throws Exception {
        Map<String, byte[]> result = new LinkedHashMap<String, byte[]>();
        try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
            for (Path path : (Iterable<Path>) stream::iterator) if (Files.isRegularFile(path)) result.put(root.relativize(path).toString().replace('\\', '/'), Files.readAllBytes(path));
        }
        return result;
    }
    private void assertByteMapsEqual(Map<String, byte[]> expected, Map<String, byte[]> actual, String label) {
        assertEquals(expected.keySet(), actual.keySet(), label + " file set");
        for (String path : expected.keySet()) assertTrue(Arrays.equals(expected.get(path), actual.get(path)), label + " bytes differ at " + path);
    }
    private void copyDirectory(Path source, Path target) throws Exception {
        try (java.util.stream.Stream<Path> stream = Files.walk(source)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                Path destination = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else Files.copy(path, destination);
            }
        }
    }
    private void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
            List<Path> paths = new ArrayList<Path>(); stream.forEach(paths::add);
            paths.sort(Comparator.reverseOrder()); for (Path path : paths) Files.deleteIfExists(path);
        }
    }
    private void run(ProcessBuilder builder) throws Exception {
        builder.redirectErrorStream(true); Process process = builder.start(); byte[] output;
        try (InputStream stream = process.getInputStream()) { output = readAll(stream); }
        int status = process.waitFor();
        if (status != 0) throw new AssertionError("maintenance parity command failed (" + status + "): " + new String(output, StandardCharsets.UTF_8));
    }
    private byte[] readAll(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count); return output.toByteArray();
    }
    private MockMvc mvc() { return org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context).build(); }
    private static Path repositoryRoot() {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null && !Files.isDirectory(current.resolve("template-source"))) current = current.getParent();
        if (current == null) throw new AssertionError("repository root not found");
        return current;
    }
}
