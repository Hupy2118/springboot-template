package com.devagentstudio.template.engine.core.v2;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;

/** Prevents reintroducing the removed V1 file-diff engine into the V2 Core. */
class LegacyCutoverArchitectureTest {
    @Test
    void v1CoreAndLegacySourceLoaderAreAbsent() {
        Path root = repositoryRoot();
        for (String relative : Arrays.asList(
                "template-engine/engine-core/src/main/java/com/devagentstudio/template/engine/core/TemplateEngine.java",
                "template-engine/engine-core/src/main/java/com/devagentstudio/template/engine/core/TemplateState.java",
                "template-engine/engine-core/src/main/java/com/devagentstudio/template/engine/core/CorePlanResult.java",
                "template-engine/engine-core/src/main/java/com/devagentstudio/template/engine/core/WorkspaceFixture.java",
                "template-engine/engine-core/src/main/java/com/devagentstudio/template/engine/source/TemplateSourceLoader.java",
                "template-source/catalog.yaml",
                "template-source/base/extension-registry.yaml",
                "template-source/capabilities/login/capability.yaml",
                "template-source/capabilities/authorization/capability.yaml"))
            assertFalse(Files.exists(root.resolve(relative)), relative);
    }

    private static Path repositoryRoot() {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null && !Files.isDirectory(current.resolve("template-source"))) current = current.getParent();
        if (current == null) throw new IllegalStateException("repository root not found");
        return current;
    }
}
