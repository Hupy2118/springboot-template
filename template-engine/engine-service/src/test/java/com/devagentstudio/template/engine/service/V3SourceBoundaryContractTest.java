package com.devagentstudio.template.engine.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V3SourceBoundaryContractTest {
    @Test
    void codeRevisionCollectorIncludesRuntimeAndExcludesMaintenanceTrees() throws Exception {
        Path root = repositoryRoot();
        Process process = new ProcessBuilder("python3", root.resolve("scripts/ci/list-code-template-release-inputs.py").toString(),
                root.resolve("template-source").toString()).directory(root.toFile()).start();
        byte[] output;
        byte[] error;
        try (InputStream stdout = process.getInputStream(); InputStream stderr = process.getErrorStream()) {
            output = readAll(stdout);
            error = readAll(stderr);
        }
        assertEquals(0, process.waitFor(), new String(error, StandardCharsets.UTF_8));
        String inputs = new String(output, StandardCharsets.UTF_8);
        assertTrue(inputs.contains("template-source/code/template-revision.txt"));
        assertTrue(inputs.contains("template-source/code/frontend/base/src/App.tsx")
                || inputs.contains("template-source/code/frontend/base/src/index.tsx"));
        assertTrue(inputs.contains("template-source/code/frontend/extensions/login/extension.yaml"));
        assertTrue(inputs.contains("template-source/code/backend/extensions/login/extension.yaml"));
        assertFalse(inputs.contains("/workspace/"));
        assertFalse(inputs.contains("/assembly/"));
        assertFalse(inputs.contains("/node_modules/"));
        assertFalse(inputs.contains("/build/"));
        assertTrue(Files.isRegularFile(root.resolve("template-source/code/template-revision.txt")));
    }

    private static byte[] readAll(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        return output.toByteArray();
    }

    private static Path repositoryRoot() {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null && !Files.isDirectory(current.resolve("template-source"))) current = current.getParent();
        if (current == null) throw new AssertionError("repository root not found");
        return current;
    }
}
