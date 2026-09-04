package com.xcodeagent.template.engine.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.xcodeagent.template.engine.source.TemplateSourceLoader;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Explicit local generation harness. It is a Failsafe test, not a production CLI. */
class Stage2GenerateIT {
    @Test
    void generatesWorkspaceFromRequestedConfig() throws Exception {
        String workspaceValue = System.getProperty("stage2.workspace");
        String configValue = System.getProperty("stage2.config", "validation/fixtures/authorization.yaml");
        if (workspaceValue == null || workspaceValue.trim().isEmpty()) return;
        Path root = repositoryRoot();
        Path workspace = Paths.get(workspaceValue).toAbsolutePath().normalize();
        assertTrue(!Files.exists(workspace) || Files.isDirectory(workspace), "workspace must be a directory when it exists");
        Files.createDirectories(workspace);
        Map<String, Object> raw = new ObjectMapper(new YAMLFactory()).readValue(
                new String(Files.readAllBytes(root.resolve(configValue)), StandardCharsets.UTF_8),
                new TypeReference<LinkedHashMap<String, Object>>() {});
        @SuppressWarnings("unchecked") Map<String, Map<String, Object>> capabilities =
                (Map<String, Map<String, Object>>) raw.get("capabilities");
        TemplateEngine engine = new TemplateEngine(new TemplateSourceLoader().load(root.resolve("template-source")));
        CorePlanResult plan = engine.plan(null, new RequestedConfig(capabilities));
        new WorkspaceFixture().apply(workspace, plan);
        Files.write(workspace.resolve(".template-engine-state.json"),
                ("{\"revision\":null,\"templateRevision\":\"" + plan.nextTemplateState().templateRevision() + "\"}\n").getBytes(StandardCharsets.UTF_8));
        assertTrue(Files.exists(workspace.resolve("frontend/package.json")));
    }

    private Path repositoryRoot() {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null && !Files.isDirectory(current.resolve("template-source"))) current = current.getParent();
        if (current == null) throw new IllegalStateException("repository root not found");
        return current;
    }
}
