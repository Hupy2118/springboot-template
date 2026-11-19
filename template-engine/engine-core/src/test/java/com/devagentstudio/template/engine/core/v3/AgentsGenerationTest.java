package com.devagentstudio.template.engine.core.v3;

import com.devagentstudio.template.engine.source.v3.CodeTemplateReleaseLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentsGenerationTest {
    @TempDir Path temporary;
    private Path code() {
        Path root = Paths.get("").toAbsolutePath();
        while (!Files.isDirectory(root.resolve("template-source"))) root = root.getParent();
        return root.resolve("template-source/code");
    }
    private Map<String,Object> request(String id) {
        Map<String,Object> result = new LinkedHashMap<String,Object>();
        if (id != null) { Map<String,Object> config = new LinkedHashMap<String,Object>(); config.put("enabled", true); config.put("config", Collections.emptyMap()); result.put(id, config); }
        return result;
    }
    @Test void profilesOwnExactlyTwoDocumentsAndV4NeverInstallsDocuments() {
        CodeTemplateRelease release = new CodeTemplateReleaseLoader(code()).load();
        V3ProjectMaterializer materializer = new V3ProjectMaterializer(release);
        ExtensionResolver resolver = new ExtensionResolver(release);
        for (String profile : Arrays.asList(null, "login", "authorization")) {
            ExtensionResolver.ResolvedExtensions resolved = resolver.resolve(request(profile));
            Map<String,byte[]> project = materializer.materialize(resolved);
            for (String side : Arrays.asList("frontend", "backend")) {
                String document = new String(project.get(side + "/AGENTS.md"), StandardCharsets.UTF_8);
                int last = -1;
                for (String id : resolved.order()) {
                    String token = "DEVAGENTSTUDIO:CAPABILITY:" + id + ":BEGIN";
                    int at = document.indexOf(token);
                    assertTrue(at > last); assertEquals(at, document.lastIndexOf(token)); last = at;
                }
                if (profile == null) assertFalse(document.contains("DEVAGENTSTUDIO:CAPABILITY:"));
                assertFalse(project.containsKey(side + "/README.md"));
            }
            for (String target : project.keySet()) {
                assertFalse(target.endsWith("AGENTS.fragment.md"));
                assertFalse(target.endsWith("docs/project-structure.md"));
                assertFalse(target.endsWith("docs/login.md"));
                assertFalse(target.endsWith("docs/authorization.md"));
            }
            assertFalse(materializer.templateState(resolved).get("installedArtifacts").toString().contains("AGENTS"));
        }
        ExtensionResolver.ResolvedExtensions base = resolver.resolve(request(null));
        ExtensionResolver.ResolvedExtensions authorization = resolver.resolve(request("authorization"));
        Map<String,Object> baseState = materializer.templateState(base);
        Map<String,Object> authState = materializer.templateState(authorization);
        // Removing compile-time document metadata cannot alter any V4 operations, payloads or state.
        CodeTemplateRelease withoutDocuments = new CodeTemplateRelease(release.revision(), release.contracts(), release.frontendBase(), release.backendBase(), release.extensions());
        V3UpdatePlanner planner = new V3UpdatePlanner(release), old = new V3UpdatePlanner(withoutDocuments);
        for (String mode : Arrays.asList("APPLY", "RECONCILE")) {
            Map<String,Object> state = "APPLY".equals(mode) ? baseState : authState;
            V3UpdateResult actual = planner.planCapabilities(state, authorization, mode);
            V3UpdateResult expected = old.planCapabilities(state, authorization, mode);
            assertEquals(expected.operations(), actual.operations());
            assertEquals(expected.nextState(), actual.nextState());
            assertEquals(expected.payloads().keySet(), actual.payloads().keySet());
            for (String path : expected.payloads().keySet()) assertArrayEquals(expected.payloads().get(path), actual.payloads().get(path));
            assertFalse(actual.operations().toString().contains("AGENTS"));
        }
        assertEquals(V3UpdateResult.Kind.NO_CHANGE, planner.planCapabilities(authState, authorization, "APPLY").kind());
    }
    @Test void loaderRejectsMissingMalformedBinarySymlinkAndOwnershipCollisions() throws Exception {
        Path source = code(), copy = temporary.resolve("code");
        Files.createDirectories(copy);
        for (String relative : Arrays.asList("template-revision.txt", "contracts", "frontend/base", "frontend/extensions", "backend/base", "backend/extensions")) {
            Path from = source.resolve(relative);
            try (java.util.stream.Stream<Path> paths = Files.walk(from)) {
                for (Path path : (Iterable<Path>) paths::iterator) {
                    if (path.toString().contains("node_modules") || path.toString().contains("/build/")) continue;
                    Path to = copy.resolve(relative).resolve(from.relativize(path));
                    if (Files.isDirectory(path)) Files.createDirectories(to); else Files.copy(path, to);
                }
            }
        }
        Path fragment = copy.resolve("backend/extensions/login/AGENTS.fragment.md");
        byte[] original = Files.readAllBytes(fragment);
        Files.delete(fragment);
        assertThrows(V3Exception.class, () -> new CodeTemplateReleaseLoader(copy).load());
        for (byte[] invalid : Arrays.asList(new byte[] {(byte) 0xff}, new byte[] {0}, "## missing markers\n".getBytes(StandardCharsets.UTF_8))) {
            Files.write(fragment, invalid); assertThrows(V3Exception.class, () -> new CodeTemplateReleaseLoader(copy).load());
        }
        Files.delete(fragment); Files.createSymbolicLink(fragment, source.resolve("backend/extensions/login/AGENTS.fragment.md"));
        assertThrows(V3Exception.class, () -> new CodeTemplateReleaseLoader(copy).load());
        Files.delete(fragment); Files.write(fragment, original);
        Path collision = copy.resolve("backend/extensions/login/AGENTS.md");
        Files.write(collision, "# collision\n".getBytes(StandardCharsets.UTF_8));
        assertThrows(V3Exception.class, () -> new CodeTemplateReleaseLoader(copy).load());
        Files.delete(collision);
        new CodeTemplateReleaseLoader(copy).load();
    }
}
