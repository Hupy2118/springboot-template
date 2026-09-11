package com.xcodeagent.template.engine.core.v2;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable, revision-identified release and the registry used by V2 reconcile planning. */
public final class TemplateRelease {
    private final String revision;
    private final Map<String, CapabilityDefinitionV2> capabilities;
    private final Map<String, StrategyDefinition> strategies;
    private final Map<String, ValidatorDefinition> validators;

    public TemplateRelease(String revision, Map<String, CapabilityDefinitionV2> capabilities,
                           Map<String, StrategyDefinition> strategies, Map<String, ValidatorDefinition> validators) {
        this.revision = revision;
        this.capabilities = Collections.unmodifiableMap(new LinkedHashMap<String, CapabilityDefinitionV2>(capabilities));
        this.strategies = Collections.unmodifiableMap(new LinkedHashMap<String, StrategyDefinition>(strategies));
        this.validators = Collections.unmodifiableMap(new LinkedHashMap<String, ValidatorDefinition>(validators));
    }
    public String revision() { return revision; }
    public Map<String, CapabilityDefinitionV2> capabilities() { return capabilities; }
    public Map<String, StrategyDefinition> strategies() { return strategies; }
    public Map<String, ValidatorDefinition> validators() { return validators; }
}
