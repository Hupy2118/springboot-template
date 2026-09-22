package com.devagentstudio.template.authoring;

import com.devagentstudio.template.engine.core.v2.StrategyDefinition;
import com.devagentstudio.template.engine.core.v2.AnchorDefinition;
import com.devagentstudio.template.engine.core.v2.TargetDefinition;
import com.devagentstudio.template.engine.source.StrategyRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityAnalyzerTest {
    @TempDir Path temporaryDirectory;

    @Test
    void producesAdditionImportAndStableAnchorDrafts() throws Exception {
        Path baseline = temporaryDirectory.resolve("baseline"); Path project = temporaryDirectory.resolve("project");
        write(baseline, "surface.ts", "import Base from 'base';\n// devagentstudio:slot\n");
        write(project, "surface.ts", "import Base from 'base';\nimport Feature from 'feature';\n/* feature */\n// devagentstudio:slot\n");
        write(project, "feature.ts", "export default Feature;\n");

        CapabilityDraft draft = new CapabilityAnalyzer().analyze("feature", baseline, project, registry());

        assertTrue(draft.compilable());
        assertEquals("feature.feature-ts", draft.additions().get(0).id());
        assertEquals(2, draft.strategies().size());
        assertEquals("ENSURE_IMPORT", draft.strategies().get(0).type());
        StrategyDraft anchor = draft.strategies().get(1);
        assertEquals("TEXT_ANCHOR_INSERT", anchor.type());
        assertEquals("feature-frontend-surface-slot", anchor.parameters().get("managedMarker"));
    }

    @Test
    void marksChangesOutsideRegisteredSurfacesUnsupported() throws Exception {
        Path baseline = temporaryDirectory.resolve("baseline"); Path project = temporaryDirectory.resolve("project");
        write(baseline, "shared.ts", "before\n"); write(project, "shared.ts", "after\n");

        CapabilityDraft draft = new CapabilityAnalyzer().analyze("feature", baseline, project, registry());

        assertFalse(draft.compilable());
        assertEquals("shared.ts", draft.unsupported().get(0).path());
    }

    @Test
    void acceptsIndentedInsertionWithBlankLinesAroundTheAnchor() throws Exception {
        Path baseline = temporaryDirectory.resolve("baseline"); Path project = temporaryDirectory.resolve("project");
        write(baseline, "surface.ts", "const base = true;\n\n// devagentstudio:slot\n");
        write(project, "surface.ts", "const base = true;\n\n\n    registerFeature();\n\t\n// devagentstudio:slot\n");

        CapabilityDraft draft = new CapabilityAnalyzer().analyze("feature", baseline, project, registry());

        assertTrue(draft.compilable());
        StrategyDraft anchor = draft.strategies().get(0);
        assertEquals("TEXT_ANCHOR_INSERT", anchor.type());
        assertEquals("/* devagentstudio:feature-frontend-surface-slot:begin */\n    registerFeature();\n/* devagentstudio:feature-frontend-surface-slot:end */\n", anchor.parameters().get("content"));
    }

    @Test
    void rejectsChangedAnchorOrBaseContent() throws Exception {
        Path baseline = temporaryDirectory.resolve("baseline"); Path project = temporaryDirectory.resolve("project");
        write(baseline, "surface.ts", "const base = true;\n// devagentstudio:slot\n");
        write(project, "surface.ts", "const changed = true;\nfeature();\n// devagentstudio:slot\n");

        CapabilityDraft baseChanged = new CapabilityAnalyzer().analyze("feature", baseline, project, registry());

        assertFalse(baseChanged.compilable());
        assertEquals("UNEXPLAINED_SURFACE_MODIFICATION", baseChanged.unsupported().get(0).reason());

        write(project, "surface.ts", "const base = true;\nfeature();\n// devagentstudio:renamed-slot\n");
        CapabilityDraft anchorChanged = new CapabilityAnalyzer().analyze("feature", baseline, project, registry());

        assertFalse(anchorChanged.compilable());
        assertEquals("UNEXPLAINED_SURFACE_MODIFICATION", anchorChanged.unsupported().get(0).reason());
    }

    @Test
    void rejectsDistinctIdentitiesThatNormalizeToTheSameManagedMarker() throws Exception {
        Path baseline = temporaryDirectory.resolve("baseline"); Path project = temporaryDirectory.resolve("project");
        write(baseline, "one.ts", "// devagentstudio:slot\n"); write(baseline, "two.ts", "// devagentstudio:slot\n");
        write(project, "one.ts", "first\n// devagentstudio:slot\n"); write(project, "two.ts", "second\n// devagentstudio:slot\n");
        Map<String, TargetDefinition> targets = new LinkedHashMap<String, TargetDefinition>();
        targets.put("a.b-c", new TargetDefinition("a.b-c", "one.ts")); targets.put("a-b.c", new TargetDefinition("a-b.c", "two.ts"));
        Map<String, AnchorDefinition> anchors = new LinkedHashMap<String, AnchorDefinition>();
        anchors.put("a.b-c:slot", new AnchorDefinition("a.b-c", "slot", "// devagentstudio:slot", "before"));
        anchors.put("a-b.c:slot", new AnchorDefinition("a-b.c", "slot", "// devagentstudio:slot", "before"));

        CapabilityDraft draft = new CapabilityAnalyzer().analyze("feature", baseline, project,
                new StrategyRegistry(targets, Collections.<String, StrategyDefinition>emptyMap(), Collections.emptyMap(), anchors));

        assertFalse(draft.compilable());
        assertEquals("MANAGED_MARKER_COLLISION", draft.unsupported().get(0).reason());
    }

    private static StrategyRegistry registry() {
        Map<String, TargetDefinition> targets = new LinkedHashMap<String, TargetDefinition>();
        targets.put("frontend.surface", new TargetDefinition("frontend.surface", "surface.ts"));
        Map<String, Object> parameters = new LinkedHashMap<String, Object>();
        parameters.put("anchor", "// devagentstudio:slot"); parameters.put("position", "before"); parameters.put("managedMarker", "existing"); parameters.put("content", "/* devagentstudio:existing:begin */\n/* devagentstudio:existing:end */\n");
        Map<String, StrategyDefinition> strategies = new LinkedHashMap<String, StrategyDefinition>();
        strategies.put("existing.slot", new StrategyDefinition("existing.slot", "surface.ts", "TEXT_ANCHOR_INSERT", 1, parameters));
        Map<String, AnchorDefinition> anchors = new LinkedHashMap<String, AnchorDefinition>();
        anchors.put("frontend.surface:slot", new AnchorDefinition("frontend.surface", "slot", "// devagentstudio:slot", "before"));
        return new StrategyRegistry(targets, strategies, Collections.emptyMap(), anchors);
    }
    private static void write(Path root, String name, String content) throws Exception { Files.createDirectories(root); Files.write(root.resolve(name), content.getBytes(StandardCharsets.UTF_8)); }
}
