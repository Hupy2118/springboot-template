package com.xcodeagent.template.engine.core.v2;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class StrategyDefinition {
    private final String id, target, type; private final int order; private final Map<String, Object> parameters;
    public StrategyDefinition(String id, String target, String type, int order, Map<String, Object> parameters) {
        this.id = id; this.target = target; this.type = type; this.order = order;
        this.parameters = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(parameters));
    }
    public String id() { return id; } public String target() { return target; } public String type() { return type; }
    public int order() { return order; } public Map<String, Object> parameters() { return parameters; }
}
