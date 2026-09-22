package com.devagentstudio.template.authoring;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** A validator registry entry planned by the authoring compiler. */
public final class ValidatorDraft {
    private final String id;
    private final Map<String, Object> parameters;
    public ValidatorDraft(String id, Map<String, Object> parameters) {
        this.id = id; this.parameters = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(parameters));
    }
    public String id() { return id; }
    public Map<String, Object> parameters() { return parameters; }
}
