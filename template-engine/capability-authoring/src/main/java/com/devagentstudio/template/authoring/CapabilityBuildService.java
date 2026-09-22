package com.devagentstudio.template.authoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.devagentstudio.template.engine.source.StrategyRegistry;
import com.devagentstudio.template.engine.source.StrategyRegistryLoader;
import com.devagentstudio.template.engine.source.TemplateSourceException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Transactional developer build: all compiler output is validated before publication. */
public final class CapabilityBuildService {
    private final Path sourceRoot;
    private final Path workbenchesRoot;
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public CapabilityBuildService(Path sourceRoot, Path workbenchesRoot) {
        this.sourceRoot = sourceRoot.toAbsolutePath().normalize();
        this.workbenchesRoot = workbenchesRoot.toAbsolutePath().normalize();
    }

    public CapabilityStatusReport build(String id) {
        Path workbench = workbench(id), project = workbench.resolve("project");
        CapabilityDraft draft = new CapabilityAnalyzer().analyze(id, workbench.resolve("baseline"), project, new StrategyRegistryLoader().load(sourceRoot));
        CapabilityStatusReport report = new CapabilityStatusReport(draft);
        if (report.status() == CapabilityStatusReport.Status.BLOCKED) throw unsupported(report);
        Path staging;
        try { staging = Files.createTempDirectory(sourceRoot.getParent(), ".capability-build-"); CapabilityCompiler.copy(sourceRoot, staging); }
        catch (IOException e) { throw new TemplateSourceException("CAPABILITY_BUILD_FAILED: " + e.getMessage()); }
        try {
            CompileState previous = new CompileStateStore().load(workbench);
            removeOwnedDraft(staging, id, previous);
            StrategyRegistry registry = new StrategyRegistryLoader().load(staging);
            CapabilityContractDraft contract = new CapabilityContractPlanner().plan(workbench.resolve("authoring.yaml"), project, draft, registry);
            CompileState current = new CapabilityCompiler().compileInto(staging, project, contract);
            new DraftTemplateSourceValidator().validate(staging);
            new RoundTripVerifier().verify(staging, project, id);
            CapabilityCompiler.replace(sourceRoot, staging);
            new CompileStateStore().save(workbench, current);
            return report;
        } catch (RuntimeException e) { CapabilityCompiler.delete(staging); throw e; }
        catch (IOException e) { CapabilityCompiler.delete(staging); throw new TemplateSourceException("CAPABILITY_BUILD_FAILED: " + e.getMessage()); }
    }

    @SuppressWarnings("unchecked")
    private void removeOwnedDraft(Path staging, String id, CompileState state) {
        Path capability = staging.resolve("capabilities").resolve(id);
        if (!Files.exists(capability)) {
            if (state != null) throw new TemplateSourceException("CAPABILITY_OWNERSHIP_CONFLICT");
            return;
        }
        if (state == null || !id.equals(state.capabilityId())) throw new TemplateSourceException("CAPABILITY_OWNERSHIP_CONFLICT");
        try {
            Map<String, Object> registry = yaml.readValue(staging.resolve("strategy-registry-v2.yaml").toFile(), Map.class);
            List<Object> strategies = (List<Object>) registry.get("strategies"); List<Object> validators = (List<Object>) registry.get("validators");
            verifyAndRemove(strategies, state.strategyIds()); verifyAndRemove(validators, state.validatorIds());
            yaml.writeValue(staging.resolve("strategy-registry-v2.yaml").toFile(), registry);
            CapabilityCompiler.delete(capability);
        } catch (IOException e) { throw new TemplateSourceException("CAPABILITY_OWNERSHIP_CONFLICT"); }
    }

    @SuppressWarnings("unchecked") private static void verifyAndRemove(List<Object> values, List<String> owned) {
        for (String id : owned) {
            int count = 0; for (Object value : values) if (value instanceof Map && id.equals(((Map<String, Object>) value).get("id"))) count++;
            if (count != 1) throw new TemplateSourceException("CAPABILITY_OWNERSHIP_CONFLICT");
        }
        java.util.Iterator<Object> iterator = values.iterator();
        while (iterator.hasNext()) { Object value = iterator.next(); if (value instanceof Map && owned.contains(((Map<String, Object>) value).get("id"))) iterator.remove(); }
    }

    private static TemplateSourceException unsupported(CapabilityStatusReport report) {
        UnsupportedChange first = report.unsupportedChanges().get(0);
        return new TemplateSourceException("CAPABILITY_CAPTURE_UNSUPPORTED: " + first.path() + " " + first.reason());
    }
    private Path workbench(String id) {
        Path value = workbenchesRoot.resolve(id).normalize();
        if (!value.startsWith(workbenchesRoot) || !Files.isDirectory(value.resolve("baseline")) || !Files.isDirectory(value.resolve("project")) || !Files.isRegularFile(value.resolve("authoring.yaml"))) throw new TemplateSourceException("WORKBENCH_DIRECTORY_MISSING: " + id);
        return value;
    }
}
