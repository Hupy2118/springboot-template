package com.xcodeagent.template.authoring;

import com.xcodeagent.template.engine.source.TemplateSourceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchInitializerTest {
    @TempDir Path temporaryDirectory;

    @Test
    void createsAnEditableBaselineWithAllTransitiveRequirements() throws Exception {
        Path workbenches = temporaryDirectory.resolve(".workbench");
        Path workbench = new WorkbenchInitializer(templateSource(), workbenches)
                .initialize("excel-export", Collections.singletonList("authorization"));

        Path baseline = workbench.resolve("baseline");
        Path project = workbench.resolve("project");
        Path authorizationFile = Paths.get("backend/src/main/java/com/cmbchina/backend/auth/adapter/web/MemberController.java");
        Path loginFile = Paths.get("backend/src/main/java/com/cmbchina/backend/auth/adapter/web/MockLoginController.java");
        assertTrue(Files.isRegularFile(baseline.resolve(authorizationFile)));
        assertTrue(Files.isRegularFile(baseline.resolve(loginFile)));
        assertArrayEquals(Files.readAllBytes(baseline.resolve(authorizationFile)), Files.readAllBytes(project.resolve(authorizationFile)));
        String authoring = new String(Files.readAllBytes(workbench.resolve("authoring.yaml")), StandardCharsets.UTF_8);
        assertTrue(authoring.contains("capabilityId: excel-export"));
        assertTrue(authoring.contains("  - authorization"));
        assertTrue(authoring.contains("templateRevision: "));
        assertTrue(authoring.matches("(?s).*baselineDigest: sha256:[0-9a-f]{64}.*"));
    }

    @Test
    void doesNotOverwriteAnExistingWorkbench() throws Exception {
        WorkbenchInitializer initializer = new WorkbenchInitializer(templateSource(), temporaryDirectory.resolve(".workbench"));
        Path workbench = initializer.initialize("excel-export", Collections.singletonList("login"));
        Files.write(workbench.resolve("authoring.yaml"), "keep-me".getBytes(StandardCharsets.UTF_8));

        assertThrows(TemplateSourceException.class,
                () -> initializer.initialize("excel-export", Collections.singletonList("login")));
        assertArrayEquals("keep-me".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(workbench.resolve("authoring.yaml")));
    }

    @Test
    void rejectsUnknownRequirementsWithoutCreatingAWorkbench() {
        Path workbenches = temporaryDirectory.resolve(".workbench");
        WorkbenchInitializer initializer = new WorkbenchInitializer(templateSource(), workbenches);

        assertThrows(TemplateSourceException.class,
                () -> initializer.initialize("excel-export", Collections.singletonList("missing-capability")));
        assertFalse(Files.exists(workbenches.resolve("excel-export")));
    }

    @Test
    void cliParsesInitAndRequiresArguments() {
        Path workbenches = temporaryDirectory.resolve(".workbench");

        Path workbench = CapabilityCli.run(templateSource(), workbenches,
                new String[] { "init", "excel-export", "--requires", "login,authorization" });

        assertTrue(Files.isRegularFile(workbench.resolve("baseline/frontend/src/pages/Login/index.tsx")));
        assertTrue(Files.isRegularFile(workbench.resolve("baseline/backend/src/main/java/com/cmbchina/backend/auth/adapter/web/MemberController.java")));
    }

    @Test
    void workflowCapturesCompilesAndVerifiesANewAddition() throws Exception {
        Path source = copyTemplateSource(); Path workbenches = temporaryDirectory.resolve(".workbench");
        CapabilityCli.run(source, workbenches, new String[] { "init", "workflow-test", "--requires", "login" });
        Path projectFile = workbenches.resolve("workflow-test/project/frontend/src/pages/Workflow.tsx");
        Files.createDirectories(projectFile.getParent()); Files.write(projectFile, "export default null;\n".getBytes(StandardCharsets.UTF_8));

        CapabilityCli.run(source, workbenches, new String[] { "capture", "workflow-test" });
        CapabilityCli.run(source, workbenches, new String[] { "compile", "workflow-test" });
        CapabilityCli.run(source, workbenches, new String[] { "verify", "workflow-test" });

        assertTrue(Files.isRegularFile(source.resolve("capabilities/workflow-test/capability-v2.yaml")));
    }

    private Path copyTemplateSource() throws Exception {
        Path source = templateSource(), destination = temporaryDirectory.resolve("template-source");
        java.util.stream.Stream<Path> paths = Files.walk(source);
        try { paths.forEach(path -> { try { Path target = destination.resolve(source.relativize(path).toString()); if (Files.isDirectory(path)) Files.createDirectories(target); else Files.copy(path, target); } catch (Exception e) { throw new RuntimeException(e); } }); }
        finally { paths.close(); }
        return destination;
    }

    private static Path templateSource() {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null && !Files.isDirectory(current.resolve("template-source"))) current = current.getParent();
        if (current == null) throw new AssertionError("repository root");
        return current.resolve("template-source");
    }
}
