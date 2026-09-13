package com.xcodeagent.template.authoring;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityCompilerTest {
    @TempDir Path temporaryDirectory;
    @Test void writesAtomicEntriesOnceAndKeepsTheSourceTreeConsistent() throws Exception {
        Path source = temporaryDirectory.resolve("source"), project = temporaryDirectory.resolve("project");
        Files.createDirectories(source.resolve("capabilities")); Files.createDirectories(project);
        Files.write(source.resolve("strategy-registry-v2.yaml"), "schemaVersion: 2\ntargets: []\nanchors: []\nstrategies: []\nvalidators: []\n".getBytes(StandardCharsets.UTF_8));
        Files.write(project.resolve("feature.ts"), "export default 1;\n".getBytes(StandardCharsets.UTF_8));
        CapabilityDraft changes = new CapabilityDraft("feature", Collections.singletonList(new AdditionDraft("feature.feature-ts", "feature.ts", "feature.ts")), Collections.<StrategyDraft>emptyList(), Collections.<UnsupportedChange>emptyList());
        Map<String,Object> validator = new LinkedHashMap<String,Object>(); validator.put("type", "CAPABILITY_POSTCONDITION"); validator.put("checks", Collections.singletonList(Collections.singletonMap("type", "FILE_EXISTS")));
        CapabilityContractDraft draft = new CapabilityContractDraft(changes, Collections.<String>emptyList(), Collections.<MigrationDraft>emptyList(), Collections.singletonList(new ValidatorDraft("feature.postcondition", validator)));
        CapabilityCompiler compiler = new CapabilityCompiler(); compiler.compile(source, project, draft);
        assertTrue(Files.isRegularFile(source.resolve("capabilities/feature/capability-v2.yaml")));
        assertTrue(new String(Files.readAllBytes(source.resolve("strategy-registry-v2.yaml")), StandardCharsets.UTF_8).contains("feature.postcondition"));
        assertThrows(RuntimeException.class, () -> compiler.compile(source, project, draft));
    }
}
