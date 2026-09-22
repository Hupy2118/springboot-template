package com.cmbchina.template.backend.maintenance;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExtensionManifestLoaderTest {
    @Test
    void rejectsConflictingDependencyContributions() throws Exception {
        Path extensions = Files.createTempDirectory("extensions");
        write(extensions.resolve("one/extension.yaml"), "id: one\nrequires: []\ncontributes:\n  mavenDependencies:\n    - groupId: example\n      artifactId: auth\n      version: 1.0\n");
        write(extensions.resolve("two/extension.yaml"), "id: two\nrequires: []\ncontributes:\n  mavenDependencies:\n    - groupId: example\n      artifactId: auth\n      version: 2.0\n");
        ExtensionManifestLoader loader = new ExtensionManifestLoader(extensions);
        assertThrows(TemplateException.class, () -> loader.resolve(Arrays.asList("one", "two")));
    }
    private static void write(Path file, String value) throws Exception { Files.createDirectories(file.getParent()); Files.write(file, value.getBytes(StandardCharsets.UTF_8)); }
}
