package com.devagentstudio.template.engine.core.v3;

import com.devagentstudio.template.engine.core.v3.ExtensionResolver.ResolvedExtensions;
import com.devagentstudio.template.engine.source.v3.CodeTemplateReleaseLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
        auth = auth.replace("applicationAnnotations: []", "applicationAnnotations:\n    - annotationClass: com.cmb.bee.auth.client.config.EnableAuthClient\n  mavenDependencies:\n    - groupId: ZA21\n      artifactId: bee-starter-auth");
        Files.write(authManifest, auth.getBytes(StandardCharsets.UTF_8));
        CodeTemplateRelease r1 = new CodeTemplateReleaseLoader(r1Root).load();
        ResolvedExtensions resolved = new ExtensionResolver(r1).resolve(requested("authorization", Collections.<String, Object>emptyMap()));
        @SuppressWarnings("unchecked") List<String> owners = (List<String>) resolved.managedContributions().get("annotation:com.cmb.bee.auth.client.config.EnableAuthClient").get("owners");
        assertEquals(Arrays.asList("authorization", "login"), owners);
        @SuppressWarnings("unchecked") List<String> mavenOwners = (List<String>) resolved.managedContributions().get("maven:ZA21:bee-starter-auth").get("owners");
        assertEquals(Arrays.asList("authorization", "login"), mavenOwners);
        assertEquals(2, resolved.managedContributions().size());

        Map<String, Object> currentState = new V3ProjectMaterializer(r1).templateState(resolved);
        Path r2Root = temporary.resolve("owners-r2/code"); copyRuntime(r1Root, r2Root); writeRevision(r2Root, "2026.09.21.1");
        Path changedLoginManifest = r2Root.resolve("backend/extensions/login/extension.yaml");
        String login = read(changedLoginManifest).replace("artifactId: bee-starter-auth", "artifactId: bee-starter-auth\n      version: 4.2.0");
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
        assertEquals(7, planner.plan(firstState, firstResolved, "RECONCILE").operations().size());
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
    private void copyRuntime(Path source, Path target) throws Exception {
        Files.createDirectories(target);
        for (String relative : Arrays.asList("frontend/base", "frontend/extensions", "backend/base", "backend/extensions")) copyDirectory(source.resolve(relative), target.resolve(relative));
        Files.copy(source.resolve("template-revision.txt"), target.resolve("template-revision.txt"));
    }
    private void copyDirectory(Path source, Path target) throws Exception {
        try (java.util.stream.Stream<Path> paths = Files.walk(source)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                Path destination = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(destination); else Files.copy(path, destination);
            }
        }
    }
    private static Path repositoryRoot() { Path path = Paths.get(System.getProperty("user.dir")).toAbsolutePath(); while (path != null && !Files.isDirectory(path.resolve("template-source"))) path = path.getParent(); if (path == null) throw new AssertionError("repository root not found"); return path; }
}
