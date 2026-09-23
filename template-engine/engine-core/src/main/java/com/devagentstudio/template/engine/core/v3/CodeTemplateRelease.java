package com.devagentstudio.template.engine.core.v3;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable V3 Runtime Source snapshot. */
public final class CodeTemplateRelease {
    private final String revision;
    private final Map<String, byte[]> frontendBase;
    private final Map<String, byte[]> backendBase;
    private final Map<String, ExtensionRelease> extensions;

    public CodeTemplateRelease(String revision, Map<String, byte[]> frontendBase, Map<String, byte[]> backendBase,
                               Map<String, ExtensionRelease> extensions) {
        this.revision = revision;
        this.frontendBase = copyBytes(frontendBase);
        this.backendBase = copyBytes(backendBase);
        this.extensions = Collections.unmodifiableMap(new LinkedHashMap<String, ExtensionRelease>(extensions));
    }
    private static Map<String, byte[]> copyBytes(Map<String, byte[]> source) {
        Map<String, byte[]> result = new LinkedHashMap<String, byte[]>();
        for (Map.Entry<String, byte[]> entry : source.entrySet()) result.put(entry.getKey(), entry.getValue().clone());
        return Collections.unmodifiableMap(result);
    }
    public String revision() { return revision; }
    public Map<String, byte[]> frontendBase() { return copyBytes(frontendBase); }
    public Map<String, byte[]> backendBase() { return copyBytes(backendBase); }
    public Map<String, ExtensionRelease> extensions() { return extensions; }
}
