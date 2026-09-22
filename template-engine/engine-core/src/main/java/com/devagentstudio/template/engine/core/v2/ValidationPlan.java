package com.devagentstudio.template.engine.core.v2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Declarative validation work for the caller to run after applying a strategy package. */
public final class ValidationPlan {
    private final List<Map<String, Object>> validators;

    public ValidationPlan(List<Map<String, Object>> validators) {
        List<Map<String, Object>> copied = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> validator : validators) copied.add(Collections.unmodifiableMap(new LinkedHashMap<String, Object>(validator)));
        this.validators = Collections.unmodifiableList(copied);
    }

    public List<Map<String, Object>> validators() { return validators; }
}
