package com.devagentstudio.template.engine.core.v3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Source and manifest contract for one resolved extension ID. */
public final class ExtensionRelease {
    private final String id;
    private final List<String> requires;
    private final Map<String, byte[]> frontendFiles;
    private final Map<String, byte[]> backendFiles;
    private final Map<String, Object> defaultConfig;
    private final List<Map<String, Object>> providers;
    private final List<Map<String, Object>> rootRoutes;
    private final List<Map<String, Object>> pageRoutes;
    private final List<Map<String, Object>> initializers;
    private final List<Map<String, Object>> errorReporters;
    private final List<String> applicationAnnotations;
    private final List<Map<String, Object>> mavenDependencies;

    public ExtensionRelease(String id, List<String> requires, Map<String, byte[]> frontendFiles,
                            Map<String, byte[]> backendFiles, Map<String, Object> defaultConfig,
                            List<Map<String, Object>> providers, List<Map<String, Object>> rootRoutes,
                            List<Map<String, Object>> pageRoutes, List<Map<String, Object>> initializers,
                            List<Map<String, Object>> errorReporters, List<String> applicationAnnotations,
                            List<Map<String, Object>> mavenDependencies) {
        this.id = id;
        this.requires = immutableList(requires);
        this.frontendFiles = immutableBytesMap(frontendFiles);
        this.backendFiles = immutableBytesMap(backendFiles);
        this.defaultConfig = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(defaultConfig));
        this.providers = immutableMapList(providers);
        this.rootRoutes = immutableMapList(rootRoutes);
        this.pageRoutes = immutableMapList(pageRoutes);
        this.initializers = immutableMapList(initializers);
        this.errorReporters = immutableMapList(errorReporters);
        this.applicationAnnotations = immutableList(applicationAnnotations);
        this.mavenDependencies = immutableMapList(mavenDependencies);
    }

    private static List<String> immutableList(List<String> source) { return Collections.unmodifiableList(new ArrayList<String>(source)); }
    private static List<Map<String, Object>> immutableMapList(List<Map<String, Object>> source) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> item : source) result.add(Collections.unmodifiableMap(new LinkedHashMap<String, Object>(item)));
        return Collections.unmodifiableList(result);
    }
    private static Map<String, byte[]> immutableBytesMap(Map<String, byte[]> source) {
        Map<String, byte[]> result = new LinkedHashMap<String, byte[]>();
        for (Map.Entry<String, byte[]> entry : source.entrySet()) result.put(entry.getKey(), entry.getValue().clone());
        return Collections.unmodifiableMap(result);
    }

    public String id() { return id; }
    public List<String> requires() { return requires; }
    public Map<String, byte[]> frontendFiles() { return copyBytes(frontendFiles); }
    public Map<String, byte[]> backendFiles() { return copyBytes(backendFiles); }
    private static Map<String, byte[]> copyBytes(Map<String, byte[]> source) {
        Map<String, byte[]> result = new LinkedHashMap<String, byte[]>();
        for (Map.Entry<String, byte[]> entry : source.entrySet()) result.put(entry.getKey(), entry.getValue().clone());
        return result;
    }
    public Map<String, Object> defaultConfig() { return defaultConfig; }
    public List<Map<String, Object>> providers() { return providers; }
    public List<Map<String, Object>> rootRoutes() { return rootRoutes; }
    public List<Map<String, Object>> pageRoutes() { return pageRoutes; }
    public List<Map<String, Object>> initializers() { return initializers; }
    public List<Map<String, Object>> errorReporters() { return errorReporters; }
    public List<String> applicationAnnotations() { return applicationAnnotations; }
    public List<Map<String, Object>> mavenDependencies() { return mavenDependencies; }
}
