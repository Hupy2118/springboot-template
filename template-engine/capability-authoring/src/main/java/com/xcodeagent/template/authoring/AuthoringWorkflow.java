package com.xcodeagent.template.authoring;

import com.xcodeagent.template.engine.source.StrategyRegistry;
import com.xcodeagent.template.engine.source.StrategyRegistryLoader;
import com.xcodeagent.template.engine.source.TemplateSourceException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Offline orchestration for the Compiler main path; it never belongs to Template Runtime. */
public final class AuthoringWorkflow {
    private final Path sourceRoot, workbenchesRoot;
    public AuthoringWorkflow(Path sourceRoot, Path workbenchesRoot) { this.sourceRoot = sourceRoot; this.workbenchesRoot = workbenchesRoot; }
    public CapabilityDraft capture(String id) { Path workbench = workbench(id); StrategyRegistry registry = new StrategyRegistryLoader().load(sourceRoot); CapabilityDraft draft = new CapabilityAnalyzer().analyze(id, workbench.resolve("baseline"), workbench.resolve("project"), registry); if (!draft.compilable()) throw new TemplateSourceException("CAPABILITY_CAPTURE_UNSUPPORTED"); return draft; }
    public void compile(String id) { Path workbench = workbench(id); CapabilityDraft draft = capture(id); CapabilityContractDraft contract = new CapabilityContractPlanner().plan(workbench.resolve("authoring.yaml"), workbench.resolve("project"), draft, new StrategyRegistryLoader().load(sourceRoot)); new CapabilityCompiler().compile(sourceRoot, workbench.resolve("project"), contract); }
    public void verify(String id) { Path workbench = workbench(id); new RoundTripVerifier().verify(sourceRoot, workbench.resolve("project"), id); }
    private Path workbench(String id) { Path result = workbenchesRoot.resolve(id).normalize(); if (!result.startsWith(workbenchesRoot) || !Files.isDirectory(result.resolve("baseline")) || !Files.isDirectory(result.resolve("project")) || !Files.isRegularFile(result.resolve("authoring.yaml"))) throw new TemplateSourceException("WORKBENCH_DIRECTORY_MISSING: " + id); return result; }
}
