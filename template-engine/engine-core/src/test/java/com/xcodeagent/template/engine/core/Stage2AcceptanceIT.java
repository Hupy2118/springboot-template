package com.xcodeagent.template.engine.core;

import com.xcodeagent.template.engine.source.TemplateSourceLoader;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Stage2AcceptanceIT {
    @Test
    void appliesAnIsolatedWorkspaceAndProducesAStableSecondPlan() throws Exception {
        Path source = repositoryRoot().resolve("template-source");
        TemplateEngine engine = new TemplateEngine(new TemplateSourceLoader().load(source));
        WorkspaceFixture fixture = new WorkspaceFixture();
        Path workspace = Files.createTempDirectory("engine-stage2-");
        try {
            CorePlanResult base = engine.plan(null, RequestedConfig.enabled());
            assertEquals(CorePlanResult.Kind.CHANGE, base.kind());
            fixture.apply(workspace, base);
            assertTrue(Files.exists(workspace.resolve("frontend/package.json")));
            assertEquals(CorePlanResult.Kind.NO_CHANGE, engine.plan(base.nextTemplateState(), RequestedConfig.enabled()).kind());

            CorePlanResult enabled = engine.plan(base.nextTemplateState(), RequestedConfig.enabled("authorization"));
            fixture.apply(workspace, enabled);
            assertTrue(Files.exists(workspace.resolve("backend/src/main/resources/xcodeagent/migrations/authorization/001-schema.sql")));
            String routes = new String(Files.readAllBytes(workspace.resolve("frontend/src/generated/capabilityRoutes.tsx")), StandardCharsets.UTF_8);
            String interceptors = new String(Files.readAllBytes(workspace.resolve("backend/src/main/java/com/cmbchina/backend/common/config/CapabilityWebMvcConfiguration.java")), StandardCharsets.UTF_8);
            assertTrue(routes.contains("/login"));
            assertTrue(routes.contains("system/authorization"));
            assertTrue(routes.contains("capabilityEntryPath: string | undefined = '/login'"));
            assertTrue(routes.contains("wrapLoginRequired"));
            assertTrue(routes.contains("wrapLoginRequired(wrapAuthorizationPage(element, page), page)"));
            assertTrue(interceptors.contains("UserWebMvcInterceptor"));
            assertTrue(interceptors.contains("ResourcePermissionInterceptor"));
            assertEquals(CorePlanResult.Kind.NO_CHANGE, engine.plan(enabled.nextTemplateState(), RequestedConfig.enabled("authorization")).kind());

            CorePlanResult removed = engine.plan(enabled.nextTemplateState(), RequestedConfig.enabled());
            assertTrue(removed.operations().stream().anyMatch(op -> op.type() == FileOperation.Type.DELETE_FILE && op.path().contains("AuthorizationManagementPage")));
            fixture.apply(workspace, removed);
            assertFalse(Files.exists(workspace.resolve("frontend/src/pages/System/AuthorizationManagementPage/index.tsx")));
        } finally { deleteTree(workspace); }
    }

    private Path repositoryRoot() {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null && !Files.isDirectory(current.resolve("template-source"))) current = current.getParent();
        if (current == null) throw new IllegalStateException("repository root not found");
        return current;
    }
    private void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> { try { Files.delete(path); } catch (Exception e) { throw new RuntimeException(e); } });
        }
    }
}
