package com.devagentstudio.template.engine.core.v3;

import com.devagentstudio.template.engine.core.v3.ExtensionResolver.ResolvedExtensions;
import com.devagentstudio.template.engine.source.v3.CodeTemplateReleaseLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V3UpdatePlannerTest {
    @TempDir Path temporary;

    @Test
    void v4CapabilityPlanIgnoresReleaseOnlyChangesAndInstallsOnlyMissingExtensions() throws Exception {
        Path r1Root = temporary.resolve("v4-r1/code"); copyRuntime(codeRoot(), r1Root); writeRevision(r1Root, "2026.09.20.1");
        CodeTemplateRelease r1 = new CodeTemplateReleaseLoader(r1Root).load();
        Map<String, Object> emptyState = new V3ProjectMaterializer(r1).templateState(new ExtensionResolver(r1).resolve(Collections.<String, Object>emptyMap()));
        Path r2Root = temporary.resolve("v4-r2/code"); copyRuntime(r1Root, r2Root); writeRevision(r2Root, "2026.09.21.1");
        CodeTemplateRelease r2 = new CodeTemplateReleaseLoader(r2Root).load();
        V3UpdatePlanner planner = new V3UpdatePlanner(r2);
        assertEquals(V3UpdateResult.Kind.NO_CHANGE, planner.planCapabilities(emptyState,
                new ExtensionResolver(r2).resolve(Collections.<String, Object>emptyMap()), "APPLY").kind());
        V3UpdateResult install = planner.planCapabilities(emptyState,
                new ExtensionResolver(r2).resolve(requested("authorization", Collections.<String, Object>emptyMap())), "APPLY");
        assertEquals(V3UpdateResult.Kind.CHANGE, install.kind());
        assertEquals(1L, install.nextState().get("stateSequence"));
        assertEquals("2026.09.21.1", install.nextState().get("templateRevision"));
        assertFalse(install.nextState().containsKey("managedConfiguration"));
        assertFalse(operationTypes(install.operations()).contains("UPSERT_MANAGED_CONFIG_BLOCK"));
        assertEquals(3, count(install.operations(), "ENSURE_CAPABILITY_CONFIG"));
        assertTrue(operationTargets(install.operations()).contains("frontend/.env"));
        assertEquals(5, count(install.operations(), "REPLACE_MANAGED_FILE"));
        assertEquals(V3UpdateResult.Kind.NO_CHANGE, planner.planCapabilities(install.nextState(),
                new ExtensionResolver(r2).resolve(requested("authorization", Collections.<String, Object>emptyMap())), "APPLY").kind());
        V3Exception removal = assertThrows(V3Exception.class, () -> planner.planCapabilities(install.nextState(),
                new ExtensionResolver(r2).resolve(Collections.<String, Object>emptyMap()), "APPLY"));
        assertEquals("CAPABILITY_REMOVAL_UNSUPPORTED", removal.code());
    }

    @Test
    void v4DoesNotRepairAssetsMissingFromAlreadyInstalledAuthorization() throws Exception {
        Path oldRoot = temporary.resolve("missing-old/code"); copyRuntime(codeRoot(), oldRoot); writeRevision(oldRoot, "2026.10.22.2");
        byte[] legacyDdl = Files.readAllBytes(oldRoot.resolve("backend/extensions/authorization/sql/authorization/schema.sql"));
        for (String asset : Arrays.asList("scripts/bootstrap-authorization.sh", "scripts/bootstrap-authorization.ps1", "sql/authorization/schema.sql"))
            Files.delete(oldRoot.resolve("backend/extensions/authorization/" + asset));
        Path oldDdl = oldRoot.resolve("backend/extensions/authorization/docs/auth/sql/ddl.sql");
        Files.createDirectories(oldDdl.getParent()); Files.write(oldDdl, legacyDdl);
        CodeTemplateRelease old = new CodeTemplateReleaseLoader(oldRoot).load();
        Map<String, Object> request = requested("authorization", Collections.<String, Object>emptyMap());
        Map<String, Object> installed = new V3ProjectMaterializer(old).templateState(new ExtensionResolver(old).resolve(request));
        assertTrue(((Map<?, ?>) installed.get("installedArtifacts")).containsKey("backend:authorization:docs/auth/sql/ddl.sql"));
        assertFalse(((Map<?, ?>) installed.get("installedArtifacts")).containsKey("backend:authorization:sql/authorization/schema.sql"));
        CodeTemplateRelease current = new CodeTemplateReleaseLoader(codeRoot()).load();
        ResolvedExtensions resolved = new ExtensionResolver(current).resolve(request);
        V3UpdatePlanner planner = new V3UpdatePlanner(current);
        assertEquals(V3UpdateResult.Kind.NO_CHANGE, planner.planCapabilities(installed, resolved, "APPLY").kind());
        assertEquals("RECONCILE_STATE_CHANGE_REQUIRED", assertThrows(V3Exception.class,
                () -> planner.planCapabilities(installed, resolved, "RECONCILE")).code());
    }

    @Test
    void runtimeScriptsRejectCrossExtensionCollisionsAndSymlinks() throws Exception {
        Path root = temporary.resolve("script-collision/code"); copyRuntime(codeRoot(), root);
        Path collision = root.resolve("backend/extensions/login/scripts/bootstrap-authorization.sh");
        Files.createDirectories(collision.getParent()); Files.write(collision, "collision".getBytes(StandardCharsets.UTF_8));
        assertThrows(V3Exception.class, () -> new CodeTemplateReleaseLoader(root).load());
        Files.delete(collision);
        Files.createSymbolicLink(collision, root.resolve("backend/extensions/authorization/scripts/bootstrap-authorization.sh"));
        assertThrows(V3Exception.class, () -> new CodeTemplateReleaseLoader(root).load());
    }

    @Test
    void runtimeSqlRejectsCrossExtensionCollisionsUnsafeDirectoriesAndBinaryContent() throws Exception {
        Path root = temporary.resolve("sql-boundary/code"); copyRuntime(codeRoot(), root);
        Path source = root.resolve("backend/extensions/authorization/sql/authorization/schema.sql");
        Path collision = root.resolve("backend/extensions/login/sql/authorization/schema.sql");
        Files.createDirectories(collision.getParent()); Files.write(collision, Files.readAllBytes(source));
        assertThrows(V3Exception.class, () -> new CodeTemplateReleaseLoader(root).load());
        Files.delete(collision);
        Files.createSymbolicLink(collision, source);
        assertThrows(V3Exception.class, () -> new CodeTemplateReleaseLoader(root).load());
        Files.delete(collision);
        Files.write(source, new byte[] {0});
        assertThrows(V3Exception.class, () -> new CodeTemplateReleaseLoader(root).load());
        Files.write(source, new byte[] {(byte) 0xff});
        assertThrows(V3Exception.class, () -> new CodeTemplateReleaseLoader(root).load());
        Files.delete(source);
        Files.delete(source.getParent()); Files.delete(source.getParent().getParent());
        Path sql = source.getParent().getParent();
        Files.createSymbolicLink(sql, root.resolve("backend/extensions/login/sql"));
        assertThrows(V3Exception.class, () -> new CodeTemplateReleaseLoader(root).load());
        Files.delete(sql); Files.write(sql, "not a directory".getBytes(StandardCharsets.UTF_8));
        assertThrows(V3Exception.class, () -> new CodeTemplateReleaseLoader(root).load());
    }

    @Test
    void releaseRefreshAddsOnlyNewArtifactsPreservesInstalledContentAndNeverDeletesRemovedArtifacts() throws Exception {
        Path r1Root = temporary.resolve("r1/code"); copyRuntime(codeRoot(), r1Root); writeRevision(r1Root, "2026.09.20.1");
        CodeTemplateRelease r1 = new CodeTemplateReleaseLoader(r1Root).load();
        Map<String, Object> requested = requested("authorization", Collections.<String, Object>emptyMap());
        ResolvedExtensions r1Resolved = new ExtensionResolver(r1).resolve(requested);
        Map<String, Object> currentState = new V3ProjectMaterializer(r1).templateState(r1Resolved);

        Path r2Root = temporary.resolve("r2/code"); copyRuntime(r1Root, r2Root); writeRevision(r2Root, "2026.09.21.1");
        Path changedFile = r2Root.resolve("frontend/extensions/login/src/apis/login.ts");
        Files.write(changedFile, (read(changedFile) + "\n// refreshed template copy\n").getBytes(StandardCharsets.UTF_8));
        Path newFile = r2Root.resolve("frontend/extensions/login/src/release-note.ts");
        Files.write(newFile, "export const releaseNote = true;\n".getBytes(StandardCharsets.UTF_8));
        Path removedFile = r2Root.resolve("backend/extensions/authorization/docs/authorization.md");
        Files.delete(removedFile);

        CodeTemplateRelease r2 = new CodeTemplateReleaseLoader(r2Root).load();
        ResolvedExtensions r2Resolved = new ExtensionResolver(r2).resolve(requested);
        V3UpdateResult result = new V3UpdatePlanner(r2).plan(currentState, r2Resolved, "APPLY");
        assertEquals(V3UpdateResult.Kind.CHANGE, result.kind());
        assertEquals(5, count(result.operations(), "REPLACE_MANAGED_FILE"));
        assertEquals(1, count(result.operations(), "ADD_FILE"));
        assertTrue(operationTargets(result.operations()).contains("frontend/src/release-note.ts"));
        assertFalse(operationTargets(result.operations()).contains("frontend/src/apis/login.ts"));
        assertFalse(operationTargets(result.operations()).contains("backend/docs/authorization.md"));
        assertFalse(operationTypes(result.operations()).contains("DELETE_FILE"));

        @SuppressWarnings("unchecked") Map<String, Map<String, Object>> nextArtifacts = (Map<String, Map<String, Object>>) result.nextState().get("installedArtifacts");
        assertEquals("2026.09.20.1", nextArtifacts.get("frontend:login:src/apis/login.ts").get("installedRevision"));
        assertEquals("2026.09.20.1", nextArtifacts.get("backend:authorization:docs/authorization.md").get("installedRevision"));
        assertEquals("2026.09.21.1", nextArtifacts.get("frontend:login:src/release-note.ts").get("installedRevision"));
        @SuppressWarnings("unchecked") Map<String, Map<String, Object>> nextContributions = (Map<String, Map<String, Object>>) result.nextState().get("managedContributions");
        assertEquals("2026.09.21.1", nextContributions.get("maven:ZA21:bee-starter-auth").get("appliedRevision"));

        V3Exception reconcile = assertThrows(V3Exception.class, () -> new V3UpdatePlanner(r2).plan(currentState, r2Resolved, "RECONCILE"));
        assertEquals("RECONCILE_STATE_CHANGE_REQUIRED", reconcile.code());
        Map<String, Object> newerState = new LinkedHashMap<String, Object>(currentState); newerState.put("templateRevision", "2026.09.22.1");
        V3Exception downgrade = assertThrows(V3Exception.class, () -> new V3UpdatePlanner(r2).plan(newerState, r2Resolved, "APPLY"));
        assertEquals("TEMPLATE_RELEASE_DOWNGRADE_UNSUPPORTED", downgrade.code());
    }

    @Test
    void contributionOwnersAggregateByResourceAndSpecChangesProduceRemoveThenEnsure() throws Exception {
        Path r1Root = temporary.resolve("owners-r1/code"); copyRuntime(codeRoot(), r1Root); writeRevision(r1Root, "2026.09.20.1");
        Path authManifest = r1Root.resolve("backend/extensions/authorization/extension.yaml");
        String auth = read(authManifest);
        auth = auth.replace("applicationAnnotations: []", "applicationAnnotations:\n    - annotationClass: com.cmb.bee.auth.client.config.EnableAuthClient");
        auth = auth.replace("  mavenDependencies:\n", "  mavenDependencies:\n    - groupId: ZA21\n      artifactId: bee-starter-auth\n");
        Files.write(authManifest, auth.getBytes(StandardCharsets.UTF_8));
        CodeTemplateRelease r1 = new CodeTemplateReleaseLoader(r1Root).load();
        ResolvedExtensions resolved = new ExtensionResolver(r1).resolve(requested("authorization", Collections.<String, Object>emptyMap()));
        @SuppressWarnings("unchecked") List<String> owners = (List<String>) resolved.managedContributions().get("annotation:com.cmb.bee.auth.client.config.EnableAuthClient").get("owners");
        assertEquals(Arrays.asList("authorization", "login"), owners);
        @SuppressWarnings("unchecked") List<String> mavenOwners = (List<String>) resolved.managedContributions().get("maven:ZA21:bee-starter-auth").get("owners");
        assertEquals(Arrays.asList("authorization", "login"), mavenOwners);
        assertEquals(3, resolved.managedContributions().size());

        Map<String, Object> currentState = new V3ProjectMaterializer(r1).templateState(resolved);
        Path r2Root = temporary.resolve("owners-r2/code"); copyRuntime(r1Root, r2Root); writeRevision(r2Root, "2026.09.21.1");
        Path changedLoginManifest = r2Root.resolve("backend/extensions/login/extension.yaml");
        String login = read(changedLoginManifest).replace("artifactId: bee-starter-auth", "artifactId: bee-starter-auth\n    version: 4.2.0");
        Files.write(changedLoginManifest, login.getBytes(StandardCharsets.UTF_8));
        Path changedAuthManifest = r2Root.resolve("backend/extensions/authorization/extension.yaml");
        String authorization = read(changedAuthManifest).replace("artifactId: bee-starter-auth", "artifactId: bee-starter-auth\n      version: 4.2.0");
        Files.write(changedAuthManifest, authorization.getBytes(StandardCharsets.UTF_8));
        CodeTemplateRelease r2 = new CodeTemplateReleaseLoader(r2Root).load();
        ResolvedExtensions r2Resolved = new ExtensionResolver(r2).resolve(requested("authorization", Collections.<String, Object>emptyMap()));
        V3UpdateResult result = new V3UpdatePlanner(r2).plan(currentState, r2Resolved, "APPLY");
        assertEquals(1, count(result.operations(), "REMOVE_MAVEN_DEPENDENCY"));
        assertEquals(1, count(result.operations(), "ENSURE_MAVEN_DEPENDENCY"));
        assertEquals("REMOVE_MAVEN_DEPENDENCY", result.operations().get(0).get("type"));
        assertEquals("ENSURE_MAVEN_DEPENDENCY", result.operations().get(1).get("type"));
    }

    @Test
    void opaqueConfigChangeCreatesStateOnlyPackageAndExactApplyIsNoChange() throws Exception {
        Path root = temporary.resolve("config/code"); copyRuntime(codeRoot(), root); writeRevision(root, "2026.09.20.1");
        CodeTemplateRelease release = new CodeTemplateReleaseLoader(root).load();
        Map<String, Object> firstRequest = requested("authorization", Collections.<String, Object>emptyMap());
        ResolvedExtensions firstResolved = new ExtensionResolver(release).resolve(firstRequest);
        Map<String, Object> firstState = new V3ProjectMaterializer(release).templateState(firstResolved);
        V3UpdatePlanner planner = new V3UpdatePlanner(release);
        assertEquals(V3UpdateResult.Kind.NO_CHANGE, planner.plan(firstState, firstResolved, "APPLY").kind());

        Map<String, Object> changedConfig = new LinkedHashMap<String, Object>(); changedConfig.put("vendorOption", Boolean.TRUE);
        ResolvedExtensions changed = new ExtensionResolver(release).resolve(requested("authorization", changedConfig));
        V3UpdateResult result = planner.plan(firstState, changed, "APPLY");
        assertEquals(V3UpdateResult.Kind.CHANGE, result.kind());
        assertTrue(result.operations().isEmpty());
        assertEquals(Boolean.TRUE, ((Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) result.nextState().get("requested")).get("authorization")).get("config")).get("vendorOption"));
        assertEquals(10, planner.plan(firstState, firstResolved, "RECONCILE").operations().size());
    }

    @Test
    void v4ConfigurationFragmentsAreUniquePerTargetAndContainOnlyNewCapabilityDefaults() throws Exception {
        CodeTemplateRelease release = new CodeTemplateReleaseLoader(codeRoot()).load();
        ExtensionResolver resolver = new ExtensionResolver(release);
        Map<String, Object> base = new V3ProjectMaterializer(release).templateState(resolver.resolve(Collections.<String, Object>emptyMap()));
        V3UpdateResult both = new V3UpdatePlanner(release).planCapabilities(base,
                resolver.resolve(requested("authorization", Collections.<String, Object>emptyMap())), "APPLY");
        assertEquals(3, count(both.operations(), "ENSURE_CAPABILITY_CONFIG"));
        for (Map<String, Object> operation : both.operations()) if ("ENSURE_CAPABILITY_CONFIG".equals(operation.get("type"))) {
            assertTrue(((String) operation.get("payloadRef")).startsWith("payload/"));
            @SuppressWarnings("unchecked") List<String> owners = (List<String>) ((Map<String, Object>) operation.get("parameters")).get("capabilityIds");
            assertFalse(owners.isEmpty());
        }
        V3UpdateResult authorizationOnly = new V3UpdatePlanner(release).planCapabilities(both.nextState(),
                resolver.resolve(requested("authorization", Collections.<String, Object>emptyMap())), "APPLY");
        assertEquals(V3UpdateResult.Kind.NO_CHANGE, authorizationOnly.kind());
    }

    private int count(List<Map<String, Object>> operations, String type) { int total = 0; for (Map<String, Object> operation : operations) if (type.equals(operation.get("type"))) total++; return total; }
    private List<String> operationTargets(List<Map<String, Object>> operations) { List<String> values = new ArrayList<String>(); for (Map<String, Object> operation : operations) values.add((String) operation.get("target")); return values; }
    private List<String> operationTypes(List<Map<String, Object>> operations) { List<String> values = new ArrayList<String>(); for (Map<String, Object> operation : operations) values.add((String) operation.get("type")); return values; }
    private Map<String, Object> requested(String id, Map<String, Object> config) {
        Map<String, Object> request = new LinkedHashMap<String, Object>(); request.put("enabled", Boolean.TRUE); request.put("config", config);
        Map<String, Object> result = new LinkedHashMap<String, Object>(); result.put(id, request); return result;
    }
    private Path codeRoot() { return repositoryRoot().resolve("template-source/code"); }
    private void writeRevision(Path root, String revision) throws Exception { Files.write(root.resolve("template-revision.txt"), (revision + "\n").getBytes(StandardCharsets.UTF_8)); }
    private String read(Path path) throws Exception { return new String(Files.readAllBytes(path), StandardCharsets.UTF_8); }
    /** 为更新测试复制完整的当前 Runtime Source，包括根目录路由契约。 */
    private void copyRuntime(Path source, Path target) throws Exception {
        Files.createDirectories(target);
        for (String relative : Arrays.asList("contracts", "frontend/base", "frontend/extensions", "backend/base", "backend/extensions")) copyDirectory(source.resolve(relative), target.resolve(relative));
        Files.copy(source.resolve("template-revision.txt"), target.resolve("template-revision.txt"));
    }
    private void copyDirectory(Path source, Path target) throws Exception {
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) throws java.io.IOException {
                if (!path.equals(source) && ignoredFixtureEntry(path, source)) return FileVisitResult.SKIP_SUBTREE;
                Path destination = target.resolve(source.relativize(path));
                Files.createDirectories(destination);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws java.io.IOException {
                if (!ignoredFixtureEntry(path, source)) Files.copy(path, target.resolve(source.relativize(path)));
                return FileVisitResult.CONTINUE;
            }
        });
    }
    private boolean ignoredFixtureEntry(Path path, Path root) {
        for (Path segment : root.relativize(path)) if ("node_modules".equals(segment.toString())) return true;
        return ".DS_Store".equals(path.getFileName().toString());
    }
    private static Path repositoryRoot() { Path path = Paths.get(System.getProperty("user.dir")).toAbsolutePath(); while (path != null && !Files.isDirectory(path.resolve("template-source"))) path = path.getParent(); if (path == null) throw new AssertionError("repository root not found"); return path; }
}
