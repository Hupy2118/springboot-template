package com.devagentstudio.template.authoring;

import com.devagentstudio.template.engine.source.TemplateSourceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchInitializerTest {
    @TempDir Path temporaryDirectory;

    @Test
    void createsAnEditableBaselineWithAllTransitiveRequirements() throws Exception {
        Path workbenches = temporaryDirectory.resolve(".workbench");
        Path workbench = new WorkbenchInitializer(templateSource(), workbenches)
                .initialize("workbench-test", Collections.singletonList("authorization"));

        Path baseline = workbench.resolve("baseline");
        Path project = workbench.resolve("project");
        Path authorizationFile = Paths.get("backend/src/main/java/com/cmbchina/backend/auth/adapter/web/MemberController.java");
        Path loginFile = Paths.get("backend/src/main/java/com/cmbchina/backend/auth/adapter/web/MockLoginController.java");
        assertTrue(Files.isRegularFile(baseline.resolve(authorizationFile)));
        assertTrue(Files.isRegularFile(baseline.resolve(loginFile)));
        assertArrayEquals(Files.readAllBytes(baseline.resolve(authorizationFile)), Files.readAllBytes(project.resolve(authorizationFile)));
        String authoring = new String(Files.readAllBytes(workbench.resolve("authoring.yaml")), StandardCharsets.UTF_8);
        assertTrue(authoring.contains("capabilityId: workbench-test"));
        assertTrue(authoring.contains("  - authorization"));
        assertTrue(authoring.contains("templateRevision: "));
        assertFalse(authoring.contains("baselineDigest"));
    }

    @Test
    void doesNotOverwriteAnExistingWorkbench() throws Exception {
        WorkbenchInitializer initializer = new WorkbenchInitializer(templateSource(), temporaryDirectory.resolve(".workbench"));
        Path workbench = initializer.initialize("workbench-test", Collections.singletonList("login"));
        Files.write(workbench.resolve("authoring.yaml"), "keep-me".getBytes(StandardCharsets.UTF_8));

        assertThrows(TemplateSourceException.class,
                () -> initializer.initialize("workbench-test", Collections.singletonList("login")));
        assertArrayEquals("keep-me".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(workbench.resolve("authoring.yaml")));
    }

    @Test
    void rejectsUnknownRequirementsWithoutCreatingAWorkbench() {
        Path workbenches = temporaryDirectory.resolve(".workbench");
        WorkbenchInitializer initializer = new WorkbenchInitializer(templateSource(), workbenches);

        assertThrows(TemplateSourceException.class,
                () -> initializer.initialize("workbench-test", Collections.singletonList("missing-capability")));
        assertFalse(Files.exists(workbenches.resolve("workbench-test")));
    }

    @Test
    void cliParsesInitAndRequiresArguments() {
        Path workbenches = temporaryDirectory.resolve(".workbench");

        Path workbench = CapabilityCli.run(templateSource(), workbenches,
                new String[] { "init", "workbench-test", "--requires", "login,authorization" });

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

    @Test
    void statusRecognizesAnExtensionSurfaceInsertAndRejectsRuntimeEdits() throws Exception {
        Path source = copyTemplateSource(); Path workbenches = temporaryDirectory.resolve(".workbench");
        CapabilityCli.run(source, workbenches, new String[] { "init", "surface-test" });
        Path project = workbenches.resolve("surface-test/project");
        Path routes = project.resolve("frontend/src/capability-extensions/routes.tsx");
        String routeSource = new String(Files.readAllBytes(routes), StandardCharsets.UTF_8)
                .replace("  // devagentstudio:capability-page-routes", "  { name: 'Page', pageId: 'page' },\n\n  // devagentstudio:capability-page-routes");
        Files.write(routes, routeSource.getBytes(StandardCharsets.UTF_8));

        CapabilityStatusReport ready = new AuthoringWorkflow(source, workbenches).status("surface-test");

        assertEquals(CapabilityStatusReport.Status.READY, ready.status());
        assertEquals(0, ready.importCount());
        assertEquals(1, ready.anchorInsertCount());

        Path runtime = project.resolve("frontend/src/routes/routeBuilder.tsx");
        Files.write(runtime, "changed\n".getBytes(StandardCharsets.UTF_8));
        CapabilityStatusReport blocked = new AuthoringWorkflow(source, workbenches).status("surface-test");

        assertEquals(CapabilityStatusReport.Status.BLOCKED, blocked.status());
        assertEquals("MODIFY_NON_SURFACE", blocked.unsupportedChanges().get(0).reason());
    }

    private Path copyTemplateSource() throws Exception {
        Path source = templateSource(), destination = temporaryDirectory.resolve("template-source");
        LegacyV2SourceTree.copy(source, destination);
        return destination;
    }

    private static Path templateSource() {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null && !Files.isDirectory(current.resolve("template-source"))) current = current.getParent();
        if (current == null) throw new AssertionError("repository root");
        return current.resolve("template-source");
    }
}
