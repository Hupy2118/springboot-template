package com.xcodeagent.template.engine.source;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateSourceContractTest {
    @Test
    void currentTemplateSourceSatisfiesTheAtomicReleaseContract() {
        Path root = findRepositoryRoot().resolve("template-source");
        new CapabilityV2Loader().load(root);
    }

    @Test
    void loginHasNoAuthorizationFrontendDependency() throws IOException {
        Path login = findRepositoryRoot().resolve("template-source/capabilities/login/frontend");
        Files.walk(login).filter(Files::isRegularFile).forEach(path -> {
            try {
                String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                assertFalse(content.contains("@/apis/authorization"), path.toString());
                assertFalse(content.contains("@/providers/AuthProvider"), path.toString());
                assertFalse(content.contains("mockAuthorizationLogin"), path.toString());
            } catch (IOException e) { throw new RuntimeException(e); }
        });
    }

    @Test
    void baseNpmDependenciesArePinnedByTheReleaseContract() throws IOException {
        Path frontend = findRepositoryRoot().resolve("template-source/base/frontend");
        String packageJson = new String(Files.readAllBytes(frontend.resolve("package.json")), StandardCharsets.UTF_8);
        String lockfile = new String(Files.readAllBytes(frontend.resolve("pnpm-lock.yaml")), StandardCharsets.UTF_8);
        assertTrue(packageJson.contains("\"packageManager\": \"pnpm@11.9.0\""));
        assertTrue(packageJson.contains("\"ahooks\": \"^3.8.1\""));
        assertTrue(lockfile.contains("ahooks@3.10.0"));
    }

    private Path findRepositoryRoot() {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null && !Files.isDirectory(current.resolve("template-source"))) current = current.getParent();
        if (current == null) throw new IllegalStateException("repository root not found");
        return current;
    }
}
