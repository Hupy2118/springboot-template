package com.devagentstudio.template.engine.core.v2;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ValidatorDefinition {
    private final String id; private final int order; private final Map<String, Object> parameters;
    public ValidatorDefinition(String id, int order, Map<String, Object> parameters) { this.id = id; this.order = order; this.parameters = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(parameters)); }
    public String id() { return id; } public int order() { return order; } public Map<String, Object> parameters() { return parameters; }
}
