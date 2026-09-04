package com.xcodeagent.template.engine.source;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TemplateSourceContractTest {
    @Test
    void currentTemplateSourceSatisfiesTheStageOneContract() {
        Path root = findRepositoryRoot().resolve("template-source");
        assertDoesNotThrow(() -> new TemplateSourceLoader().load(root));
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

    private Path findRepositoryRoot() {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null && !Files.isDirectory(current.resolve("template-source"))) current = current.getParent();
        if (current == null) throw new IllegalStateException("repository root not found");
        return current;
    }
}
