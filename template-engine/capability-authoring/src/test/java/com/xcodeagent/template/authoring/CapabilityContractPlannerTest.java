package com.xcodeagent.template.authoring;

import com.xcodeagent.template.engine.core.v2.AnchorDefinition;
import com.xcodeagent.template.engine.core.v2.TargetDefinition;
import com.xcodeagent.template.engine.source.StrategyRegistry;
import com.xcodeagent.template.engine.source.TemplateSourceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityContractPlannerTest {
    @TempDir Path temporaryDirectory;

    @Test
    void keepsMigrationsExplicitAndBuildsNonEmptyPostconditionChecks() throws Exception {
        Path project = temporaryDirectory.resolve("project");
        write(project, "feature.ts", "export default 1;\n");
        write(project, "db/schema.sql", "create table feature(id bigint);\n");
        Path metadata = temporaryDirectory.resolve("authoring.yaml");
        Files.write(metadata, ("capabilityId: feature\nrequires: []\ntemplateRevision: R1\nbaselineDigest: sha256:abc\n"
                + "migrations:\n  - id: schema\n    source: db/schema.sql\n    bootstrapConsumerPath: backend/db/schema.sql\n    executionTrigger: AUTHORIZATION_BOOTSTRAP_DDL\n").getBytes(StandardCharsets.UTF_8));
        CapabilityDraft changes = new CapabilityDraft("feature",
                Collections.singletonList(new AdditionDraft("feature.feature-ts", "feature.ts", "feature.ts")),
                Collections.<StrategyDraft>emptyList(), Collections.<UnsupportedChange>emptyList());

        CapabilityContractDraft draft = new CapabilityContractPlanner().plan(metadata, project, changes, registry());

        assertEquals(1, draft.migrations().size());
        assertEquals("backend/db/schema.sql", draft.migrations().get(0).target());
        assertEquals("feature.postcondition", draft.validators().get(0).id());
        assertEquals("CAPABILITY_POSTCONDITION", draft.validators().get(0).parameters().get("type"));
        assertTrue(((java.util.List<?>) draft.validators().get(0).parameters().get("checks")).size() > 0);
    }

    @Test
    void rejectsMigrationSourceThatDoesNotDeclareTheDeploymentConsumer() throws Exception {
        Path project = temporaryDirectory.resolve("project");
        write(project, "db/schema.sql", "sql");
        Path metadata = temporaryDirectory.resolve("authoring.yaml");
        Files.write(metadata, ("capabilityId: feature\nrequires: []\ntemplateRevision: R1\nbaselineDigest: sha256:abc\n"
                + "migrations:\n  - id: schema\n    source: db/schema.sql\n    target: migrations/schema.sql\n    bootstrapConsumerPath: backend/db/schema.sql\n    executionTrigger: AUTHORIZATION_BOOTSTRAP_DDL\n").getBytes(StandardCharsets.UTF_8));

        assertThrows(TemplateSourceException.class, () -> new CapabilityContractPlanner().plan(metadata, project,
                new CapabilityDraft("feature", Collections.<AdditionDraft>emptyList(), Collections.<StrategyDraft>emptyList(), Collections.<UnsupportedChange>emptyList()), registry()));
    }

    @Test
    void doesNotInferMigrationsWhenAuthoringMetadataDoesNotDeclareThem() throws Exception {
        Path project = temporaryDirectory.resolve("project"); write(project, "feature.ts", "export default 1;\n");
        Path metadata = temporaryDirectory.resolve("authoring.yaml");
        Files.write(metadata, "capabilityId: feature\nrequires: []\ntemplateRevision: R1\nbaselineDigest: sha256:abc\n".getBytes(StandardCharsets.UTF_8));

        CapabilityContractDraft draft = new CapabilityContractPlanner().plan(metadata, project,
                new CapabilityDraft("feature", Collections.singletonList(new AdditionDraft("feature.feature-ts", "feature.ts", "feature.ts")),
                        Collections.<StrategyDraft>emptyList(), Collections.<UnsupportedChange>emptyList()), registry());

        assertTrue(draft.migrations().isEmpty());
    }

    @Test
    void rejectsACompilableDraftWithoutAnyPostconditionCheck() throws Exception {
        Path project = temporaryDirectory.resolve("project"); Files.createDirectories(project);
        Path metadata = temporaryDirectory.resolve("authoring.yaml");
        Files.write(metadata, "capabilityId: feature\nrequires: []\ntemplateRevision: R1\nbaselineDigest: sha256:abc\n".getBytes(StandardCharsets.UTF_8));

        assertThrows(TemplateSourceException.class, () -> new CapabilityContractPlanner().plan(metadata, project,
                new CapabilityDraft("feature", Collections.<AdditionDraft>emptyList(), Collections.<StrategyDraft>emptyList(), Collections.<UnsupportedChange>emptyList()), registry()));
    }

    private static StrategyRegistry registry() {
        Map<String, TargetDefinition> targets = new LinkedHashMap<String, TargetDefinition>();
        targets.put("frontend.surface", new TargetDefinition("frontend.surface", "surface.ts"));
        Map<String, AnchorDefinition> anchors = new LinkedHashMap<String, AnchorDefinition>();
        anchors.put("frontend.surface:slot", new AnchorDefinition("frontend.surface", "slot", "// xcodeagent:slot", "before"));
        return new StrategyRegistry(targets, Collections.emptyMap(), Collections.emptyMap(), anchors);
    }

    private static void write(Path root, String path, String content) throws Exception {
        Path file = root.resolve(path); Files.createDirectories(file.getParent()); Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }
}
