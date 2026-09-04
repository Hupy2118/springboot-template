package com.xcodeagent.template.engine.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Persistable Stage2 state; it intentionally contains no project or service lifecycle data. */
public final class TemplateState {
    private final String templateRevision;
    private final Map<String, String> managedFiles;
    private final Map<String, Object> requested;
    private final Map<String, Object> effective;

    public TemplateState(String templateRevision, Map<String, String> managedFiles, Map<String, Object> requested, Map<String, Object> effective) {
        this.templateRevision = templateRevision;
        this.managedFiles = Collections.unmodifiableMap(new LinkedHashMap<String, String>(managedFiles));
        this.requested = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(requested));
        this.effective = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(effective));
    }
    public String templateRevision() { return templateRevision; }
    public Map<String, String> managedFiles() { return managedFiles; }
    public Map<String, Object> requested() { return requested; }
    public Map<String, Object> effective() { return effective; }
}
