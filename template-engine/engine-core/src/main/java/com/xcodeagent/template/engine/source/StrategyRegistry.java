package com.xcodeagent.template.engine.source;

import com.xcodeagent.template.engine.core.v2.StrategyDefinition;
import com.xcodeagent.template.engine.core.v2.AnchorDefinition;
import com.xcodeagent.template.engine.core.v2.TargetDefinition;
import com.xcodeagent.template.engine.core.v2.ValidatorDefinition;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Read-only view of the published V2 target, strategy, and validator registries. */
public final class StrategyRegistry {
    private final Map<String, TargetDefinition> targets;
    private final Map<String, StrategyDefinition> strategies;
    private final Map<String, ValidatorDefinition> validators;
    private final Map<String, AnchorDefinition> anchors;

    public StrategyRegistry(Map<String, TargetDefinition> targets, Map<String, StrategyDefinition> strategies,
                            Map<String, ValidatorDefinition> validators) {
        this(targets, strategies, validators, Collections.<String, AnchorDefinition>emptyMap());
    }

    public StrategyRegistry(Map<String, TargetDefinition> targets, Map<String, StrategyDefinition> strategies,
                            Map<String, ValidatorDefinition> validators, Map<String, AnchorDefinition> anchors) {
        this.targets = Collections.unmodifiableMap(new LinkedHashMap<String, TargetDefinition>(targets));
        this.strategies = Collections.unmodifiableMap(new LinkedHashMap<String, StrategyDefinition>(strategies));
        this.validators = Collections.unmodifiableMap(new LinkedHashMap<String, ValidatorDefinition>(validators));
        this.anchors = Collections.unmodifiableMap(new LinkedHashMap<String, AnchorDefinition>(anchors));
    }

    public Map<String, TargetDefinition> targets() { return targets; }
    public Map<String, StrategyDefinition> strategies() { return strategies; }
    public Map<String, ValidatorDefinition> validators() { return validators; }
    /** Keyed by targetId + ":" + anchorKey. */
    public Map<String, AnchorDefinition> anchors() { return anchors; }
    public AnchorDefinition anchor(String targetId, String anchorKey) { return anchors.get(targetId + ":" + anchorKey); }
}
