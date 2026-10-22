package com.devagentstudio.template.engine.core.v3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Normalizes requested capabilities and resolves the current Release dependency closure. */
public final class ExtensionResolver {
    private static final Pattern ID = Pattern.compile("^[a-z0-9][a-z0-9_-]*$");
    private final CodeTemplateRelease release;

    public ExtensionResolver(CodeTemplateRelease release) { this.release = release; }

    @SuppressWarnings("unchecked")
    public ResolvedExtensions resolve(Map<String, Object> requestedInput) {
        if (requestedInput == null) throw new V3Exception("BAD_REQUEST", "requestedConfig.capabilities is required", 400);
        Map<String, Map<String, Object>> requested = new TreeMap<String, Map<String, Object>>();
        for (Map.Entry<String, Object> entry : requestedInput.entrySet()) {
            String id = entry.getKey();
            if (!ID.matcher(id).matches()) throw new V3Exception("BAD_REQUEST", "invalid capability id: " + id, 400);
            Object rawValue = entry.getValue();
            if (!(rawValue instanceof Map)) throw new V3Exception("BAD_REQUEST", "capability request must be an object: " + id, 400);
            Map<String, Object> value = (Map<String, Object>) rawValue;
            Object enabled = value.get("enabled");
            Object config = value.get("config");
            if (!(enabled instanceof Boolean) || !(config instanceof Map))
                throw new V3Exception("BAD_REQUEST", "capability request requires boolean enabled and object config: " + id, 400);
            if (Boolean.TRUE.equals(enabled)) {
                if (!release.extensions().containsKey(id)) throw new V3Exception("CAPABILITY_UNKNOWN", "unknown capability: " + id, 400);
                Map<String, Object> state = new LinkedHashMap<String, Object>();
                state.put("enabled", Boolean.TRUE); state.put("config", deepObject((Map<String, Object>) config));
                requested.put(id, state);
            }
        }

        Set<String> selected = new HashSet<String>();
        for (String id : requested.keySet()) select(id, selected);
        List<String> order = new ArrayList<String>();
        Set<String> visited = new HashSet<String>();
        List<String> roots = new ArrayList<String>(selected);
        Collections.sort(roots);
        for (String id : roots) visitOrder(id, visited, order);
        Map<String, Map<String, Object>> effective = new TreeMap<String, Map<String, Object>>();
        for (String id : order) {
            Map<String, Object> state = requested.get(id);
            if (state == null) {
                state = new LinkedHashMap<String, Object>();
                state.put("enabled", Boolean.TRUE);
                state.put("config", deepObject(release.extensions().get(id).defaultConfig()));
            }
            effective.put(id, state);
        }
        return new ResolvedExtensions(requested, effective, order, aggregate(order));
    }

    private void select(String id, Set<String> selected) {
        if (!selected.add(id)) return;
        ExtensionRelease extension = release.extensions().get(id);
        if (extension == null) throw new V3Exception("CAPABILITY_UNKNOWN", "unknown capability: " + id, 400);
        for (String dependency : extension.requires()) select(dependency, selected);
    }

    private void visitOrder(String id, Set<String> visited, List<String> order) {
        if (!visited.add(id)) return;
        List<String> dependencies = new ArrayList<String>(release.extensions().get(id).requires());
        Collections.sort(dependencies);
        for (String dependency : dependencies) visitOrder(dependency, visited, order);
        order.add(id);
    }

    private Map<String, Map<String, Object>> aggregate(List<String> order) {
        Map<String, Map<String, Object>> result = new TreeMap<String, Map<String, Object>>();
        for (String id : order) {
            ExtensionRelease extension = release.extensions().get(id);
            for (String annotation : extension.applicationAnnotations()) {
                String key = "annotation:" + annotation;
                addOwner(result, key, id, "JAVA_ANNOTATION", "backend/src/main/java/com/cmbchina/backend/Application.java",
                        singleton("annotationClass", annotation));
            }
            for (Map<String, Object> dependency : extension.mavenDependencies()) {
                String groupId = (String) dependency.get("groupId");
                String artifactId = (String) dependency.get("artifactId");
                String key = "maven:" + groupId + ":" + artifactId;
                addOwner(result, key, id, "MAVEN_DEPENDENCY", "backend/pom.xml", dependency);
            }
        }
        for (Map<String, Object> contribution : result.values()) {
            @SuppressWarnings("unchecked") List<String> owners = (List<String>) contribution.get("owners");
            Collections.sort(owners);
            contribution.put("appliedRevision", release.revision());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private void addOwner(Map<String, Map<String, Object>> result, String key, String owner, String type,
                          String target, Map<String, Object> spec) {
        Map<String, Object> contribution = result.get(key);
        if (contribution == null) {
            contribution = new LinkedHashMap<String, Object>();
            contribution.put("type", type); contribution.put("target", target);
            contribution.put("owners", new ArrayList<String>()); contribution.put("spec", new LinkedHashMap<String, Object>(spec));
            result.put(key, contribution);
        } else if (!contribution.get("spec").equals(spec)) {
            String code = "MAVEN_DEPENDENCY".equals(type) ? "MAVEN_DEPENDENCY_CONFLICT" : "TEMPLATE_SOURCE_INVALID";
            throw new V3Exception(code, "conflicting managed contribution spec for " + key, 500);
        }
        ((List<String>) contribution.get("owners")).add(owner);
    }

    private Map<String, Object> singleton(String key, Object value) {
        Map<String, Object> result = new LinkedHashMap<String, Object>(); result.put(key, value); return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deepObject(Map<String, Object> input) {
        Map<String, Object> result = new TreeMap<String, Object>();
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map) result.put(entry.getKey(), deepObject((Map<String, Object>) value));
            else if (value instanceof List) result.put(entry.getKey(), deepList((List<Object>) value));
            else result.put(entry.getKey(), value);
        }
        return result;
    }
    @SuppressWarnings("unchecked")
    private List<Object> deepList(List<Object> input) {
        List<Object> result = new ArrayList<Object>();
        for (Object value : input) result.add(value instanceof Map ? deepObject((Map<String, Object>) value)
                : value instanceof List ? deepList((List<Object>) value) : value);
        return result;
    }

    public static final class ResolvedExtensions {
        private final Map<String, Map<String, Object>> requested;
        private final Map<String, Map<String, Object>> effective;
        private final List<String> order;
        private final Map<String, Map<String, Object>> managedContributions;
        private ResolvedExtensions(Map<String, Map<String, Object>> requested, Map<String, Map<String, Object>> effective,
                                   List<String> order, Map<String, Map<String, Object>> contributions) {
            this.requested = requested; this.effective = effective; this.order = order; this.managedContributions = contributions;
        }
        public Map<String, Map<String, Object>> requested() { return requested; }
        public Map<String, Map<String, Object>> effective() { return effective; }
        public List<String> order() { return order; }
        public Map<String, Map<String, Object>> managedContributions() { return managedContributions; }
    }
}
