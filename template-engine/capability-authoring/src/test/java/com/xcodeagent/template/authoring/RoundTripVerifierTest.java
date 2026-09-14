package com.xcodeagent.template.authoring;

import com.xcodeagent.template.engine.source.TemplateSourceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoundTripVerifierTest {
    @TempDir Path temporaryDirectory;
    @Test void acceptsGeneratedProjectWithNormalizedLineEndings() throws Exception { Path workbench = new WorkbenchInitializer(source(), temporaryDirectory.resolve(".workbench")).initialize("draft-roundtrip", Collections.singletonList("login")); Path project = workbench.resolve("project"); Path readme = project.resolve("backend/README.md"); Files.write(readme, new String(Files.readAllBytes(readme), StandardCharsets.UTF_8).replace("\n", "\r\n").concat("\r\n").getBytes(StandardCharsets.UTF_8)); assertDoesNotThrow(() -> new RoundTripVerifier().verify(source(), project, "login")); }
    @Test void rejectsMissingOrChangedGeneratedFiles() throws Exception { Path workbench = new WorkbenchInitializer(source(), temporaryDirectory.resolve(".workbench")).initialize("draft-roundtrip", Collections.singletonList("login")); Files.write(workbench.resolve("project/backend/README.md"), "changed".getBytes(StandardCharsets.UTF_8)); assertThrows(TemplateSourceException.class, () -> new RoundTripVerifier().verify(source(), workbench.resolve("project"), "login")); }
    @Test void buildsCapabilityWithAnImportAndAnchorInsertion() throws Exception {
        Path draftSource = temporaryDirectory.resolve("template-source");
        CapabilityCompiler.copy(source(), draftSource);
        Path workbenches = temporaryDirectory.resolve(".workbench");
        Path workbench = new WorkbenchInitializer(draftSource, workbenches).initialize("excel-export-test", Collections.<String>emptyList());
        Path routes = workbench.resolve("project/frontend/src/capability-extensions/routes.tsx");
        String edited = new String(Files.readAllBytes(routes), StandardCharsets.UTF_8)
                .replace("import type { PageRouteDefinition } from '@/typings/routes';", "import type { PageRouteDefinition } from '@/typings/routes';\nimport Home from '@/pages/Home';")
                .replace("  // xcodeagent:capability-page-routes", "  {\n    pageId: 'home',\n    modulePath: 'home',\n    component: Home,\n  },\n  // xcodeagent:capability-page-routes");
        Files.write(routes, edited.getBytes(StandardCharsets.UTF_8));

        assertDoesNotThrow(() -> new CapabilityBuildService(draftSource, workbenches).build("excel-export-test"));
        String registry = new String(Files.readAllBytes(draftSource.resolve("strategy-registry-v2.yaml")), StandardCharsets.UTF_8);
        assertTrue(registry.contains("xcodeagent:excel-export-test-frontend-capability-routes-capability-page-routes:begin"));
    }
    private static Path source() { Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath(); while (current != null && !Files.isDirectory(current.resolve("template-source"))) current = current.getParent(); if (current == null) throw new AssertionError("repo"); return current.resolve("template-source"); }
}
