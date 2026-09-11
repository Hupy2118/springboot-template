package com.xcodeagent.template.engine.core.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.xcodeagent.template.engine.source.CapabilityV2Loader;
import com.xcodeagent.template.engine.source.TemplateSourceLoader;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconcileDecisionEngineTest {
    @Test
    @SuppressWarnings("unchecked")
    void everyV1FileIsRepresentedByExactlyOneV2Addition() throws Exception {
        Path root = sourceRoot();
        TemplateRelease release = new CapabilityV2Loader().load(new TemplateSourceLoader().load(root));
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        for (String capabilityId : new String[] {"login", "authorization"}) {
            Map<String, Object> legacy = yaml.readValue(root.resolve("capabilities").resolve(capabilityId).resolve("capability.yaml").toFile(), Map.class);
            Set<String> expected = new HashSet<String>();
            for (Object raw : (List<Object>) legacy.get("files")) {
                Map<String, Object> file = (Map<String, Object>) raw;
                expected.add(file.get("source") + "|" + file.get("target"));
            }
            Set<String> actual = new HashSet<String>();
            for (AdditionDefinition addition : release.capabilities().get(capabilityId).additions())
                actual.add(addition.source() + "|" + addition.target());
            assertEquals(expected, actual, capabilityId);
        }
    }

    @Test
    void enableThenMaintainUsesMetadataPolicyDeterministically() {
        TemplateRelease release = release();
        TemplateStateV2 empty = new TemplateStateV2("2026.09.04.1", Collections.<String, CapabilityState>emptyMap(),
                Collections.<String, CapabilityState>emptyMap(), Collections.<String, AppliedAdditionState>emptyMap());
        Map<String, CapabilityState> requested = new LinkedHashMap<String, CapabilityState>();
        requested.put("authorization", new CapabilityState(true, Collections.<String, Object>emptyMap()));
        ReconcileDecisionEngine engine = new ReconcileDecisionEngine();

        UpdateResult enabled = engine.decide(empty, requested, ReconcileDecisionEngine.Mode.APPLY, release);
        assertEquals(UpdateResult.Kind.CHANGE, enabled.kind());
        assertTrue(enabled.nextTemplateState().effective().containsKey("login"));
        assertTrue(enabled.nextTemplateState().appliedAdditions().containsKey("authorization.role-page"));

        UpdateResult maintained = engine.decide(enabled.nextTemplateState(), requested, ReconcileDecisionEngine.Mode.RECONCILE, release);
        assertEquals(UpdateResult.Kind.CHANGE, maintained.kind());
        boolean hasMaintainStrategy = false;
        for (ModificationStrategy strategy : maintained.strategies()) if ("frontend.authorization.reconcile-auth-provider".equals(strategy.strategyId())) hasMaintainStrategy = true;
        assertTrue(!hasMaintainStrategy);
    }

    private static TemplateRelease release() {
        return new CapabilityV2Loader().load(new TemplateSourceLoader().load(sourceRoot()));
    }

    private static Path sourceRoot() {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null && !Files.isDirectory(current.resolve("template-source"))) current = current.getParent();
        return current.resolve("template-source");
    }
}
