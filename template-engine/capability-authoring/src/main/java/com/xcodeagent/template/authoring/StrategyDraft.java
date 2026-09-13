package com.xcodeagent.template.authoring;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** One authoring-time draft for exactly one V2 atomic strategy. */
public final class StrategyDraft {
    private final String type, targetId, anchorKey; private final Map<String, Object> parameters;
    public StrategyDraft(String type, String targetId, String anchorKey, Map<String, Object> parameters) { this.type = type; this.targetId = targetId; this.anchorKey = anchorKey; this.parameters = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(parameters)); }
    public String type() { return type; } public String targetId() { return targetId; } public String anchorKey() { return anchorKey; } public Map<String, Object> parameters() { return parameters; }
}
