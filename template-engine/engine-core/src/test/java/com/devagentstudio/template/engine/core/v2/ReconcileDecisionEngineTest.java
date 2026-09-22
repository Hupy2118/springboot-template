package com.devagentstudio.template.engine.core.v2;

import com.devagentstudio.template.engine.source.CapabilityV2Loader;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconcileDecisionEngineTest {
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

    @Test
    void missingAdditionIsAStateChangeAndCreatePrecedesRegistryStrategies() {
        TemplateRelease release = release();
        ReconcileDecisionEngine engine = new ReconcileDecisionEngine();
        Map<String, CapabilityState> requested = new LinkedHashMap<String, CapabilityState>();
        requested.put("authorization", new CapabilityState(true, Collections.<String, Object>emptyMap()));
        TemplateStateV2 empty = new TemplateStateV2(release.revision(), Collections.<String, CapabilityState>emptyMap(),
                Collections.<String, CapabilityState>emptyMap(), Collections.<String, AppliedAdditionState>emptyMap());
        UpdateResult enabled = engine.decide(empty, requested, ReconcileDecisionEngine.Mode.APPLY, release);
        Map<String, AppliedAdditionState> incomplete = new LinkedHashMap<String, AppliedAdditionState>(enabled.nextTemplateState().appliedAdditions());
        incomplete.remove("login.login-page");
        TemplateStateV2 current = new TemplateStateV2(release.revision(), enabled.nextTemplateState().requested(),
                enabled.nextTemplateState().effective(), incomplete);

        UpdateResult repaired = engine.decide(current, requested, ReconcileDecisionEngine.Mode.APPLY, release);
        assertEquals(UpdateResult.Kind.CHANGE, repaired.kind());
        assertEquals("login.login-page", repaired.strategies().get(0).strategyId());
        assertEquals("ADD_FILE", repaired.strategies().get(0).type());
        assertEquals("login", repaired.strategies().get(0).parameters().get("capabilityId"));
        assertTrue(!repaired.strategies().get(0).parameters().containsKey("precondition"));
        assertTrue(repaired.nextTemplateState().appliedAdditions().containsKey("login.login-page"));
    }

    @Test
    void appliedAdditionIdentityChangeIsRejectedBeforePlanning() {
        TemplateRelease release = release();
        Map<String, CapabilityState> requested = new LinkedHashMap<String, CapabilityState>();
        requested.put("login", new CapabilityState(true, Collections.<String, Object>emptyMap()));
        Map<String, AppliedAdditionState> additions = new LinkedHashMap<String, AppliedAdditionState>();
        additions.put("login.login-page", new AppliedAdditionState("login", "frontend/src/pages/Renamed/index.tsx", release.revision()));
        TemplateStateV2 current = new TemplateStateV2(release.revision(), requested, requested, additions);
        assertThrows(com.devagentstudio.template.engine.source.TemplateSourceException.class,
                () -> new ReconcileDecisionEngine().decide(current, requested, ReconcileDecisionEngine.Mode.APPLY, release));
    }

    private static TemplateRelease release() {
        return new CapabilityV2Loader().load(sourceRoot());
    }

    private static Path sourceRoot() {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null && !Files.isDirectory(current.resolve("template-source"))) current = current.getParent();
        return current.resolve("template-source");
    }
}
