package com.devagentstudio.template.engine.core.v3;

import java.util.ArrayList;
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

    /** V4 only installs capabilities absent from the caller's state. Parameters and release refreshes are excluded. */
    @SuppressWarnings("unchecked")
    public V3UpdateResult planCapabilities(Map<String, Object> current, ExtensionResolver.ResolvedExtensions resolved, String mode) {
        RevisionVersion currentRevision = RevisionVersion.parse((String) current.get("templateRevision"));
        if (currentRevision.compareTo(RevisionVersion.parse(release.revision())) > 0)
            throw new V3Exception("TEMPLATE_RELEASE_DOWNGRADE_UNSUPPORTED", "current TemplateState is newer than this server Release", 409);
        Map<String, Map<String, Object>> effective = (Map<String, Map<String, Object>>) current.get("effective");
        Map<String, Map<String, Object>> artifacts = (Map<String, Map<String, Object>>) current.get("installedArtifacts");
        Map<String, Map<String, Object>> contributions = (Map<String, Map<String, Object>>) current.get("managedContributions");
        for (String id : effective.keySet()) {
            if (!resolved.effective().containsKey(id))
                throw new V3Exception("CAPABILITY_REMOVAL_UNSUPPORTED", "removing capability is unsupported: " + id, 409);
            if (!effective.get(id).equals(resolved.effective().get(id)))
                throw new V3Exception("CAPABILITY_UPGRADE_UNSUPPORTED", "changing an installed capability is unsupported: " + id, 409);
        }
        if ("RECONCILE".equals(mode)) {
            if (!effective.keySet().equals(resolved.effective().keySet()) || !release.revision().equals(current.get("templateRevision")))
                throw new V3Exception("RECONCILE_STATE_CHANGE_REQUIRED", "RECONCILE cannot change TemplateState", 409);
            List<OperationDraft> repair = new ArrayList<OperationDraft>();
            for (String target : REGISTRIES) repair.add(OperationDraft.replace(target, registryBytes(resolved, target)));
            for (Map.Entry<String, Map<String, Object>> item : contributions.entrySet()) repair.add(ensure(item.getKey(), item.getValue()));
            Map<String, Object> repaired = new LinkedHashMap<String, Object>(current);
            repaired.remove("managedConfiguration");
            repaired.put("stateSequence", current.getOrDefault("stateSequence", 0));
            return compile(current, repaired, mode, repair);
        }
        if (effective.keySet().equals(resolved.effective().keySet())) return V3UpdateResult.noChange(current);
        Map<String, Map<String, Object>> nextArtifacts = new TreeMap<String, Map<String, Object>>(artifacts);
        List<OperationDraft> operations = new ArrayList<OperationDraft>();
        List<String> newCapabilityIds = new ArrayList<String>();
        for (String id : resolved.order()) if (!effective.containsKey(id)) newCapabilityIds.add(id);
        for (Map.Entry<String, Map<String, Object>> item : artifactStates(resolved.order()).entrySet()) {
            if (artifacts.containsKey(item.getKey())) continue;
            String target = (String) item.getValue().get("target");
            for (Map<String, Object> prior : artifacts.values()) if (target.equals(prior.get("target")))
                throw new V3Exception("TEMPLATE_STATE_INVALID", "new Artifact target conflicts with installed Artifact", 409);
            nextArtifacts.put(item.getKey(), item.getValue());
            operations.add(OperationDraft.add(item.getKey(), target, artifactBytes(item.getKey())));
        }
        operations.addAll(capabilityConfigOperations(newCapabilityIds));
        Map<String, Map<String, Object>> nextContributions = deepContributions(resolved.managedContributions());
        for (Map.Entry<String, Map<String, Object>> item : contributions.entrySet()) {
            Map<String, Object> next = nextContributions.get(item.getKey());
            if (next == null || !sameSpec(item.getValue(), next))
                throw new V3Exception("CAPABILITY_UPGRADE_UNSUPPORTED", "installed Contribution changed: " + item.getKey(), 409);
            next.put("appliedRevision", item.getValue().get("appliedRevision"));
        }
        for (Map.Entry<String, Map<String, Object>> item : nextContributions.entrySet())
            if (!contributions.containsKey(item.getKey())) operations.add(ensure(item.getKey(), item.getValue()));
        for (String target : REGISTRIES) operations.add(OperationDraft.replace(target, registryBytes(resolved, target)));
        Map<String, Object> next = state(resolved, nextArtifacts, nextContributions, null);
        long sequence = ((Number) current.getOrDefault("stateSequence", 0)).longValue();
        if (sequence == Long.MAX_VALUE) throw new V3Exception("TEMPLATE_STATE_INVALID", "stateSequence is exhausted", 409);
        next.put("stateSequence", sequence + 1);
        return compile(current, next, mode, operations);
    }

    /** V4 config payloads are extension fragments, never reconstructed project files or user values. */
    private List<OperationDraft> capabilityConfigOperations(List<String> ids) {
        Map<String, Object> application = new LinkedHashMap<String, Object>();
        Map<String, Object> dev = new LinkedHashMap<String, Object>();
        List<String> applicationOwners = new ArrayList<String>();
        List<String> devOwners = new ArrayList<String>();
        boolean login = false;
        for (String id : ids) {
            ExtensionRelease extension = release.extensions().get(id);
            if (!extension.applicationYaml().isEmpty()) { mergeConfig(application, extension.applicationYaml(), ""); applicationOwners.add(id); }
            Map<String, Object> profile = extension.applicationProfileYaml().get("dev");
            if (profile != null && !profile.isEmpty()) { mergeConfig(dev, profile, ""); devOwners.add(id); }
            if ("login".equals(id)) login = true;
        }
        List<OperationDraft> result = new ArrayList<OperationDraft>();
        if (!application.isEmpty()) result.add(OperationDraft.capabilityConfig("backend/src/main/resources/application.yml", "yaml", applicationOwners, ApplicationConfigProjector.dump(application)));
        if (!dev.isEmpty()) result.add(OperationDraft.capabilityConfig("backend/src/main/resources/application-dev.yml", "yaml", devOwners, ApplicationConfigProjector.dump(dev)));
        if (login) result.add(OperationDraft.capabilityConfig(ApplicationConfigProjector.FRONTEND_TARGET, "dotenv", Collections.singletonList("login"), ApplicationConfigProjector.loginEnvDefaults(dev)));
        return result;
    }
    @SuppressWarnings("unchecked") private void mergeConfig(Map<String, Object> target, Map<String, Object> source, String prefix) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object incoming = entry.getValue(); Object existing = target.get(entry.getKey());
            if (incoming instanceof Map) {
                if (existing == null) { Map<String, Object> copy = new LinkedHashMap<String, Object>(); mergeConfig(copy, (Map<String, Object>) incoming, path); target.put(entry.getKey(), copy); }
                else if (existing instanceof Map) mergeConfig((Map<String, Object>) existing, (Map<String, Object>) incoming, path);
                else throw packageError("capability configuration conflict: " + path);
            } else if (existing == null) target.put(entry.getKey(), incoming);
            else if (!existing.equals(incoming)) throw packageError("capability configuration conflict: " + path);
        }
    }

    @SuppressWarnings("unchecked")
    public V3UpdateResult plan(Map<String, Object> current, ExtensionResolver.ResolvedExtensions resolved, String mode) {
        return plan(current, resolved, mode, null);
    }

    @SuppressWarnings("unchecked")
    public V3UpdateResult plan(Map<String, Object> current, ExtensionResolver.ResolvedExtensions resolved, String mode, Map<String, Object> applicationConfig) {
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
        Map<String, Object> currentApplication = (Map<String, Object>) current.get("managedConfiguration");
        Map<String, Object> nextState = state(resolved, nextArtifacts, targetContributions, applicationConfig);
        if (current.containsKey("stateSequence")) nextState.put("stateSequence", current.get("stateSequence"));
        boolean releaseChanged = !release.revision().equals(current.get("templateRevision"));
        boolean requestedChanged = !currentRequested.equals(resolved.requested());
        boolean effectiveChanged = !currentEffective.equals(resolved.effective());
        boolean artifactsChanged = !sameKeys(currentArtifacts, nextArtifacts);
        boolean contributionsChanged = !sameContributionState(currentContributions, targetContributions);
        boolean configurationChanged = !sameNullable(currentApplication, applicationConfig);
        boolean stateChanged = releaseChanged || requestedChanged || effectiveChanged || artifactsChanged || contributionsChanged || configurationChanged;

        if ("RECONCILE".equals(mode)) {
            if (stateChanged) throw new V3Exception("RECONCILE_STATE_CHANGE_REQUIRED", "RECONCILE cannot change TemplateState", 409);
            List<OperationDraft> repair = new ArrayList<OperationDraft>();
            for (String target : REGISTRIES) repair.add(OperationDraft.replace(target, registryBytes(resolved, target)));
            for (String target : yamlTargets(resolved)) repair.add(OperationDraft.replaceYaml(target, projectBytes(resolved, target)));
            for (Map.Entry<String, Map<String, Object>> item : currentContributions.entrySet()) repair.add(ensure(item.getKey(), item.getValue()));
            repair.addAll(configOperations(currentApplication));
            return compile(current, current, mode, repair);
        }

        if (!stateChanged) return V3UpdateResult.noChange(current);
        List<OperationDraft> operations = new ArrayList<OperationDraft>();
        operations.addAll(diffContributions(currentContributions, targetContributions));
        operations.addAll(adds);
        boolean projectionChanged = releaseChanged || !currentEffective.keySet().equals(resolved.effective().keySet());
        if (projectionChanged) {
            for (String target : REGISTRIES) operations.add(OperationDraft.replace(target, registryBytes(resolved, target)));
            for (String target : yamlTargets(resolved)) operations.add(OperationDraft.replaceYaml(target, projectBytes(resolved, target)));
        }
        if (configurationChanged) operations.addAll(configOperations(applicationConfig));
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
    private List<String> yamlTargets(ExtensionResolver.ResolvedExtensions resolved) {
        java.util.Set<String> targets=new java.util.TreeSet<String>();
        for(String id:resolved.order()) { ExtensionRelease extension=release.extensions().get(id); if(!extension.applicationYaml().isEmpty()) targets.add("backend/src/main/resources/application.yml"); for(String profile:extension.applicationProfileYaml().keySet()) targets.add("backend/src/main/resources/application-" + profile + ".yml"); }
        return new ArrayList<String>(targets);
    }
    private byte[] projectBytes(ExtensionResolver.ResolvedExtensions resolved, String target) {
        byte[] value=new V3ProjectMaterializer(release).materialize(resolved).get(target); if(value==null) throw packageError("YAML projection source disappeared: " + target); return value;
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
                                      Map<String, Map<String, Object>> contributions, Map<String, Object> applicationConfig) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("schemaVersion", 3); result.put("templateRevision", release.revision());
        result.put("requested", resolved.requested()); result.put("effective", resolved.effective());
        result.put("installedArtifacts", artifacts); result.put("managedContributions", contributions);
        if (applicationConfig != null) result.put("managedConfiguration", deep(applicationConfig));
        return result;
    }
    private boolean sameNullable(Object left, Object right) { return left == null ? right == null : left.equals(right); }
    private List<OperationDraft> configOperations(Map<String, Object> applicationConfig) {
        if (applicationConfig == null) return Collections.emptyList();
        throw new V3Exception("CONFIG_TARGET_CONFLICT", "V3 application configuration refresh is unsupported; use local parameter updates", 409);
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
        private static OperationDraft replaceYaml(String target, byte[] payload) {
            return new OperationDraft("REPLACE_MANAGED_YAML", "replace-managed-yaml:" + target, target, 4, Collections.<String, Object>emptyMap(), payload);
        }
        private static OperationDraft contribution(String type, String id, String target, Map<String, Object> parameters) {
            int phase = type.startsWith("REMOVE_") ? 0 : "ENSURE_MAVEN_DEPENDENCY".equals(type) ? 2 : 3;
            return new OperationDraft(type, id, target, phase, new LinkedHashMap<String, Object>(parameters), null);
        }
        private static OperationDraft config(String target, String blockId, byte[] payload) {
            Map<String, Object> parameters = new LinkedHashMap<String, Object>(); parameters.put("blockId", blockId);
            return new OperationDraft("UPSERT_MANAGED_CONFIG_BLOCK", "upsert-config:" + target, target, 4, parameters, payload);
        }
        private static OperationDraft capabilityConfig(String target, String format, List<String> capabilityIds, byte[] payload) {
            Map<String, Object> parameters = new LinkedHashMap<String, Object>();
            parameters.put("format", format); parameters.put("capabilityIds", new ArrayList<String>(capabilityIds));
            return new OperationDraft("ENSURE_CAPABILITY_CONFIG", "ensure-config:" + target, target, 4, parameters, payload);
        }
    }
}
