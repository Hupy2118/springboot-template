package com.devagentstudio.template.engine.service.appupdate;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;

/** Exact request object; rejects unknown fields and Jackson scalar coercion. */
public final class AppUpdateRequest {
    private final String currentVersion;

    /** Parses only an object containing one string field named currentVersion. */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public AppUpdateRequest(JsonNode value) {
        if (value == null || !value.isObject() || value.size() != 1 || !value.path("currentVersion").isTextual()) {
            throw new IllegalArgumentException("request must contain only the currentVersion string");
        }
        currentVersion = value.get("currentVersion").textValue();
    }

    public String getCurrentVersion() { return currentVersion; }
}
