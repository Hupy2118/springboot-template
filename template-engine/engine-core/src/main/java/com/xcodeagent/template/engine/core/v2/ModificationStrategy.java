package com.xcodeagent.template.engine.core.v2;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Declarative operation for XCodeAgent; core never reads or transforms the target file. */
public final class ModificationStrategy {
    private final String type;
    private final String strategyId;
    private final String target;
    private final int order;
    private final Map<String, Object> parameters;

    public ModificationStrategy(String type, String strategyId, String target, int order, Map<String, Object> parameters) {
        this.type = type;
        this.strategyId = strategyId;
        this.target = target;
        this.order = order;
        this.parameters = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(parameters));
    }

    public String type() { return type; }
    public String strategyId() { return strategyId; }
    public String target() { return target; }
    public int order() { return order; }
    public Map<String, Object> parameters() { return parameters; }
}
