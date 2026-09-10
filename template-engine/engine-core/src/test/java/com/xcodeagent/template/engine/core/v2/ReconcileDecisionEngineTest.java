package com.xcodeagent.template.engine.core.v2;

import com.xcodeagent.template.engine.source.CapabilityV2Loader;
import com.xcodeagent.template.engine.source.TemplateSourceContext;
import com.xcodeagent.template.engine.source.TemplateSourceLoader;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconcileDecisionEngineTest {
    @Test
    void enableThenMaintainUsesMetadataPolicyDeterministically() {
        TemplateRelease release = release();
        TemplateStateV2 empty = new TemplateStateV2("2026.09.04.1", release.digest(), Collections.<String, CapabilityState>emptyMap(),
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
        assertTrue(hasMaintainStrategy);
    }

    private static TemplateRelease release() {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null && !Files.isDirectory(current.resolve("template-source"))) current = current.getParent();
        TemplateSourceContext context = new TemplateSourceLoader().load(current.resolve("template-source"));
        return new CapabilityV2Loader().load(context);
    }
}
