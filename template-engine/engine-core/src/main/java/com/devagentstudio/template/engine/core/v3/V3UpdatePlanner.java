package com.devagentstudio.template.engine.core.v3;

import com.devagentstudio.template.engine.core.v2.StateDigest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Plans V3 state transitions and the six fixed Workspace Operations. */
public final class V3UpdatePlanner {
    private static final List<String> REGISTRIES = V3ProjectMaterializer.REGISTRY_TARGETS;
    private final CodeTemplateRelease release;

    public V3UpdatePlanner(CodeTemplateRelease release) { this.release = release; }

    @SuppressWarnings("unchecked")
    public V3UpdateResult plan(Map<String, Object> current, ExtensionResolver.ResolvedExtensions resolved, String mode) {
        RevisionVersion currentRevision = RevisionVersion.parse((String) current.get("templateRevision"));
        RevisionVersion releaseRevision = RevisionVersion.parse(release.revision());
        if (currentRevision.compareTo(releaseRevision) > 0)
            throw new V3Exception("TEMPLATE_RELEASE_DOWNGRADE_UNSUPPORTED", "current TemplateState is newer than this server Release", 409);

        Map<String, Map<String, Object>> currentRequested = (Map<String, Map<String, Object>>) current.get("requested");
        Map<String, Map<String, Object>> currentEffective = (Map<String, Map<String, Object>>) current.get("effective");
        Map<String, Map<String, Object>> currentArtifacts = (Map<String, Map<String, Object>>) current.get("installedArtifacts");
        Map<String, Map<String, Object>> currentContributions = (Map<String, Map<String, Object>>) current.get("managedContributions");
        for (String id : currentEffective.keySet()) if (!resolved.effective().containsKey(id))
            throw new V3Exception("CAPABILITY_REMOVAL_UNSUPPORTED", "removing capability is unsupported: " + id, 409);

        Map<String, Map<String, Object>> targetArtifacts = artifactStates(resolved.order());
        Map<String, Map<String, Object>> nextArtifacts = new TreeMap<String, Map<String, Object>>();
        for (Map.Entry<String, Map<String, Object>> old : currentArtifacts.entrySet()) nextArtifacts.put(old.getKey(), deep(old.getValue()));
        List<OperationDraft> adds = new ArrayList<OperationDraft>();
        for (Map.Entry<String, Map<String, Object>> candidate : targetArtifacts.entrySet()) {
            if (currentArtifacts.containsKey(candidate.getKey())) continue;
            nextArtifacts.put(candidate.getKey(), candidate.getValue());
            adds.add(OperationDraft.add(candidate.getKey(), (String) candidate.getValue().get("target"),
                    artifactBytes(candidate.getKey())));
        }

        Map<String, Map<String, Object>> targetContributions = deepContributions(resolved.managedContributions());
        Map<String, Object> nextState = state(resolved, nextArtifacts, targetContributions);
        boolean releaseChanged = !release.revision().equals(current.get("templateRevision"));
        boolean requestedChanged = !currentRequested.equals(resolved.requested());
        boolean effectiveChanged = !currentEffective.equals(resolved.effective());
        boolean artifactsChanged = !sameKeys(currentArtifacts, nextArtifacts);
        boolean contributionsChanged = !sameContributionState(currentContributions, targetContributions);
        boolean stateChanged = releaseChanged || requestedChanged || effectiveChanged || artifactsChanged || contributionsChanged;

        if ("RECONCILE".equals(mode)) {
            if (stateChanged) throw new V3Exception("RECONCILE_STATE_CHANGE_REQUIRED", "RECONCILE cannot change TemplateState", 409);
            List<OperationDraft> repair = new ArrayList<OperationDraft>();
            for (String target : REGISTRIES) repair.add(OperationDraft.replace(target, registryBytes(resolved, target)));
            for (Map.Entry<String, Map<String, Object>> item : currentContributions.entrySet()) repair.add(ensure(item.getKey(), item.getValue()));
            return compile(current, current, mode, repair);
        }

        if (!stateChanged) return V3UpdateResult.noChange(current);
        List<OperationDraft> operations = new ArrayList<OperationDraft>();
        operations.addAll(diffContributions(currentContributions, targetContributions));
        operations.addAll(adds);
        boolean projectionChanged = releaseChanged || !currentEffective.keySet().equals(resolved.effective().keySet());
        if (projectionChanged) for (String target : REGISTRIES) operations.add(OperationDraft.replace(target, registryBytes(resolved, target)));
        return compile(current, nextState, mode, operations);
    }

    private Map<String, Map<String, Object>> artifactStates(List<String> order) {
        Map<String, Map<String, Object>> result = new TreeMap<String, Map<String, Object>>();
        for (String id : order) {
            ExtensionRelease extension = release.extensions().get(id);
            addArtifacts(result, id, "frontend", extension.frontendFiles());
            addArtifacts(result, id, "backend", extension.backendFiles());
        }
        return result;
    }
    private void addArtifacts(Map<String, Map<String, Object>> result, String id, String side, Map<String, byte[]> files) {
        for (String path : files.keySet()) {
            String artifactId = side + ":" + id + ":" + path;
            Map<String, Object> state = new LinkedHashMap<String, Object>();
            state.put("capabilityId", id); state.put("target", side + "/" + path); state.put("installedRevision", release.revision());
            result.put(artifactId, state);
        }
    }
    private byte[] artifactBytes(String artifactId) {
        String[] parts = artifactId.split(":", 3);
        if (parts.length != 3) throw packageError("invalid Artifact ID " + artifactId);
        ExtensionRelease extension = release.extensions().get(parts[1]);
        byte[] value = "frontend".equals(parts[0]) ? extension.frontendFiles().get(parts[2]) : extension.backendFiles().get(parts[2]);
        if (value == null) throw packageError("Artifact source disappeared: " + artifactId);
        return value;
    }

    private List<OperationDraft> diffContributions(Map<String, Map<String, Object>> current,
                                                   Map<String, Map<String, Object>> target) {
        List<OperationDraft> result = new ArrayList<OperationDraft>();
        for (Map.Entry<String, Map<String, Object>> old : current.entrySet()) {
            Map<String, Object> next = target.get(old.getKey());
            if (next == null || !sameSpec(old.getValue(), next)) result.add(remove(old.getKey(), old.getValue()));
        }
        for (Map.Entry<String, Map<String, Object>> next : target.entrySet()) {
            Map<String, Object> old = current.get(next.getKey());
            if (old == null || !sameSpec(old, next.getValue())) result.add(ensure(next.getKey(), next.getValue()));
        }
        return result;
    }
    @SuppressWarnings("unchecked")
    private OperationDraft ensure(String key, Map<String, Object> value) {
        String type = (String) value.get("type");
        Map<String, Object> spec = (Map<String, Object>) value.get("spec");
        return "JAVA_ANNOTATION".equals(type)
                ? OperationDraft.contribution("ENSURE_JAVA_ANNOTATION", "ensure-annotation:" + spec.get("annotationClass"), (String) value.get("target"), spec)
                : OperationDraft.contribution("ENSURE_MAVEN_DEPENDENCY", "ensure-maven:" + spec.get("groupId") + ":" + spec.get("artifactId"), (String) value.get("target"), spec);
    }
    @SuppressWarnings("unchecked")
    private OperationDraft remove(String key, Map<String, Object> value) {
        String type = (String) value.get("type");
        Map<String, Object> spec = (Map<String, Object>) value.get("spec");
        return "JAVA_ANNOTATION".equals(type)
                ? OperationDraft.contribution("REMOVE_JAVA_ANNOTATION", "remove-annotation:" + spec.get("annotationClass"), (String) value.get("target"), spec)
                : OperationDraft.contribution("REMOVE_MAVEN_DEPENDENCY", "remove-maven:" + spec.get("groupId") + ":" + spec.get("artifactId"), (String) value.get("target"), spec);
    }
    @SuppressWarnings("unchecked")
    private boolean sameSpec(Map<String, Object> left, Map<String, Object> right) {
        return left.get("type").equals(right.get("type")) && left.get("target").equals(right.get("target"))
                && ((Map<String, Object>) left.get("spec")).equals((Map<String, Object>) right.get("spec"));
    }
    private boolean sameContributionState(Map<String, Map<String, Object>> left, Map<String, Map<String, Object>> right) {
        if (!left.keySet().equals(right.keySet())) return false;
        for (String key : left.keySet()) if (!left.get(key).equals(right.get(key))) return false;
        return true;
    }
    private boolean sameKeys(Map<String, Map<String, Object>> left, Map<String, Map<String, Object>> right) { return left.keySet().equals(right.keySet()); }

    private Map<String, Map<String, Object>> deepContributions(Map<String, Map<String, Object>> values) {
        Map<String, Map<String, Object>> result = new TreeMap<String, Map<String, Object>>();
        for (Map.Entry<String, Map<String, Object>> item : values.entrySet()) result.put(item.getKey(), deep(item.getValue()));
        return result;
    }
    @SuppressWarnings("unchecked")
    private Map<String, Object> deep(Map<String, Object> value) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> item : value.entrySet()) {
            Object nested = item.getValue();
            if (nested instanceof Map) result.put(item.getKey(), deep((Map<String, Object>) nested));
            else if (nested instanceof List) result.put(item.getKey(), new ArrayList<Object>((List<Object>) nested));
            else result.put(item.getKey(), nested);
        }
        return result;
    }
    private Map<String, Object> state(ExtensionResolver.ResolvedExtensions resolved, Map<String, Map<String, Object>> artifacts,
                                      Map<String, Map<String, Object>> contributions) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("schemaVersion", 3); result.put("templateRevision", release.revision());
        result.put("requested", resolved.requested()); result.put("effective", resolved.effective());
        result.put("installedArtifacts", artifacts); result.put("managedContributions", contributions);
        return result;
    }

    private V3UpdateResult compile(Map<String, Object> current, Map<String, Object> next, String mode, List<OperationDraft> drafts) {
        Collections.sort(drafts, new Comparator<OperationDraft>() {
            @Override public int compare(OperationDraft left, OperationDraft right) {
                int phase = Integer.compare(left.phase, right.phase);
                if (phase != 0) return phase;
                int target = left.target.compareTo(right.target);
                return target != 0 ? target : left.operationId.compareTo(right.operationId);
            }
        });
        List<Map<String, Object>> operations = new ArrayList<Map<String, Object>>();
        Map<String, byte[]> payloads = new TreeMap<String, byte[]>();
        for (int index = 0; index < drafts.size(); index++) {
            OperationDraft draft = drafts.get(index);
            Map<String, Object> operation = new LinkedHashMap<String, Object>();
            operation.put("schemaVersion", 1); operation.put("operationId", draft.operationId); operation.put("index", index);
            operation.put("type", draft.type); operation.put("target", draft.target); operation.put("parameters", draft.parameters);
            String payloadRef = draft.payload == null ? null : "payload/" + draft.target;
            operation.put("payloadRef", payloadRef);
            operations.add(operation);
            if (payloadRef != null) payloads.put(payloadRef, draft.payload);
        }
        return V3UpdateResult.change(current, next, mode, operations, payloads);
    }

    private byte[] registryBytes(ExtensionResolver.ResolvedExtensions resolved, String target) {
        String projectTarget = target;
        Map<String, byte[]> project = new V3ProjectMaterializer(release).materialize(resolved);
        byte[] bytes = project.get(projectTarget);
        if (bytes == null) throw packageError("managed registry source missing: " + target);
        return bytes;
    }
    private V3Exception packageError(String message) { return new V3Exception("PACKAGE_BUILD_FAILED", message, 500); }

    private static final class OperationDraft {
        private final String type, operationId, target;
        private final int phase;
        private final Map<String, Object> parameters;
        private final byte[] payload;
        private OperationDraft(String type, String operationId, String target, int phase, Map<String, Object> parameters, byte[] payload) {
            this.type = type; this.operationId = operationId; this.target = target; this.phase = phase; this.parameters = parameters; this.payload = payload;
        }
        private static OperationDraft add(String artifactId, String target, byte[] payload) {
            Map<String, Object> parameters = Collections.emptyMap();
            return new OperationDraft("ADD_FILE", "add-file:" + artifactId, target, 1, parameters, payload);
        }
        private static OperationDraft replace(String target, byte[] payload) {
            return new OperationDraft("REPLACE_MANAGED_FILE", "replace-managed:" + target, target, 4, Collections.<String, Object>emptyMap(), payload);
        }
        private static OperationDraft contribution(String type, String id, String target, Map<String, Object> parameters) {
            int phase = type.startsWith("REMOVE_") ? 0 : "ENSURE_MAVEN_DEPENDENCY".equals(type) ? 2 : 3;
            return new OperationDraft(type, id, target, phase, new LinkedHashMap<String, Object>(parameters), null);
        }
    }
}
