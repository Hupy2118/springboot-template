package com.xcodeagent.template.engine.source;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CapabilityV2LoaderContractTest {
    @Test
    void currentReleasePassesV2PublicationGates() {
        assertDoesNotThrow(() -> new CapabilityV2Loader().load(sourceRoot()));
    }

    @Test
    void rejectsMissingTargetAnchorMarkerAndMigrationConsumerMismatch() throws Exception {
        assertRejected("strategy-registry-v2.yaml", "frontend/src/generated/capabilityProviders.tsx", "frontend/src/generated/missing.ts");
        assertRejected("strategy-registry-v2.yaml", "// xcodeagent:capability-providers", "// missing-anchor");
        assertRejected("strategy-registry-v2.yaml", "xcodeagent:login-provider:begin", "xcodeagent:other-provider:begin");
        assertRejected("capabilities/authorization/capability-v2.yaml", "bootstrapConsumerPath: backend/docs/auth/sql/ddl.sql", "bootstrapConsumerPath: backend/docs/auth/sql/other.sql");
    }

    @Test
    void rejectsInvalidDependencyGraphExactParametersUnsupportedGenerateTypeAndTrigger() throws Exception {
        assertRejected("capabilities/authorization/capability-v2.yaml", "- id: login", "- id: missing-capability");
        assertRejected("capabilities/login/capability-v2.yaml", "requires: []", "requires: [login]");
        assertRejected("strategy-registry-v2.yaml", "parameters: { importStatement: \"import { GlobalContextProvider } from '@/providers'\" }",
                "parameters: { importStatement: \"import { GlobalContextProvider } from '@/providers'\", unexpected: true }");
        assertRejected("strategy-registry-v2.yaml",
                "- { id: frontend.login.import-provider, targetId: frontend.capability-providers, type: ENSURE_IMPORT, order: 100, parameters: { importStatement: \"import { GlobalContextProvider } from '@/providers'\" } }",
                "- { id: frontend.login.import-provider, targetId: frontend.capability-providers, type: ENSURE_NPM_DEPENDENCY, order: 100, parameters: { name: react, version: 18.0.0 } }");
        assertRejected("capabilities/authorization/capability-v2.yaml", "executionTrigger: AUTHORIZATION_BOOTSTRAP_DDL", "executionTrigger: UNKNOWN_TRIGGER");
    }

    @Test
    void rejectsContentChangesReusingThePublishedRevision() throws Exception {
        assertRejected("base/README.md", "#", "# publication-gate-change\n#");
    }

    private static void assertRejected(String relative, String expected, String replacement) throws Exception {
        Path copy = copySource();
        Path file = copy.resolve(relative);
        String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        if (!content.contains(expected)) throw new AssertionError("fixture text missing: " + expected);
        Files.write(file, content.replace(expected, replacement).getBytes(StandardCharsets.UTF_8));
        assertThrows(TemplateSourceException.class, () -> new CapabilityV2Loader().load(copy));
        delete(copy);
    }

    private static Path copySource() throws IOException {
        Path source = sourceRoot();
        Path copy = Files.createTempDirectory("capability-v2-contract-");
        Files.walk(source).forEach(path -> {
            try {
                Path relative = source.relativize(path);
                if (relative.toString().contains("node_modules")) return;
                Path target = copy.resolve(relative.toString());
                if (Files.isDirectory(path)) Files.createDirectories(target);
                else { Files.createDirectories(target.getParent()); Files.copy(path, target); }
            } catch (IOException e) { throw new RuntimeException(e); }
        });
        return copy;
    }

    private static void delete(Path root) throws IOException {
        Files.walk(root).sorted(Comparator.reverseOrder()).forEach(path -> {
            try { Files.delete(path); } catch (IOException e) { throw new RuntimeException(e); }
        });
    }

    private static Path sourceRoot() {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null && !Files.isDirectory(current.resolve("template-source"))) current = current.getParent();
        if (current == null) throw new AssertionError("repository root");
        return current.resolve("template-source");
    }
}
