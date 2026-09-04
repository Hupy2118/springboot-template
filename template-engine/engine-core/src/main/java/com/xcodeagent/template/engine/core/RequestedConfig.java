package com.xcodeagent.template.engine.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Complete desired capability configuration; omitted capabilities are disabled. */
public final class RequestedConfig {
    private final Map<String, Map<String, Object>> capabilities;

    public RequestedConfig(Map<String, Map<String, Object>> capabilities) {
        this.capabilities = Collections.unmodifiableMap(new LinkedHashMap<String, Map<String, Object>>(capabilities));
    }

    public Map<String, Map<String, Object>> capabilities() { return capabilities; }

    public static RequestedConfig enabled(String... ids) {
        Map<String, Map<String, Object>> values = new LinkedHashMap<String, Map<String, Object>>();
        for (String id : ids) values.put(id, Collections.<String, Object>singletonMap("enabled", Boolean.TRUE));
        return new RequestedConfig(values);
    }
}
