package com.xcodeagent.template.engine.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xcodeagent.template.engine.core.CorePlanResult;
import com.xcodeagent.template.engine.core.FileOperation;
import com.xcodeagent.template.engine.core.v2.ModificationStrategy;
import com.xcodeagent.template.engine.core.v2.TemplateStateV2;
import com.xcodeagent.template.engine.core.v2.UpdateResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds only response bytes. It never creates a caller workspace or durable server state. */
final class PackageBuilder {
    private static final long ZIP_TIMESTAMP = 0L;
    private final ObjectMapper json;
    private final Path sourceRoot;
    PackageBuilder(ObjectMapper json, Path sourceRoot) { this.json = json; this.sourceRoot = sourceRoot.toAbsolutePath().normalize(); }

    byte[] generatedProject(CorePlanResult plan) {
        if (plan.kind() != CorePlanResult.Kind.CHANGE) throw new ServiceException("PACKAGE_BUILD_FAILED", "initial generation must have changes", 500);
        Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        for (FileOperation operation : plan.operations()) {
            if (operation.type() == FileOperation.Type.DELETE_FILE) throw new ServiceException("PACKAGE_BUILD_FAILED", "initial generation cannot delete files", 500);
            entries.put(operation.path(), bytes(operation.content()));
        }
        entries.put(".xcodeagent/template-state.json", jsonBytes(EngineMapper.state(plan.nextTemplateState())));
        return zip(entries);
    }

    byte[] generatedProject(CorePlanResult plan, TemplateStateV2 nextTemplateState) {
        if (plan.kind() != CorePlanResult.Kind.CHANGE) throw new ServiceException("PACKAGE_BUILD_FAILED", "initial generation must have changes", 500);
        Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        for (FileOperation operation : plan.operations()) {
            if (operation.type() == FileOperation.Type.DELETE_FILE) throw new ServiceException("PACKAGE_BUILD_FAILED", "initial generation cannot delete files", 500);
            entries.put(operation.path(), bytes(operation.content()));
        }
        entries.put(".xcodeagent/template-state.json", jsonBytes(EngineMapper.stateV2(nextTemplateState)));
        return zip(entries);
    }

    byte[] generatedProject(Map<String, String> project, TemplateStateV2 nextTemplateState) {
        Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        for (Map.Entry<String, String> file : project.entrySet()) entries.put(file.getKey(), bytes(file.getValue()));
        entries.put(".xcodeagent/template-state.json", jsonBytes(EngineMapper.stateV2(nextTemplateState)));
        return zip(entries);
    }

    byte[] updatePackage(CorePlanResult plan) {
        Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        entries.put("change-set.json", jsonBytes(EngineMapper.changeSet(plan)));
        entries.put("next-template-state.json", jsonBytes(EngineMapper.state(plan.nextTemplateState())));
        for (FileOperation operation : plan.operations()) {
            if (operation.type() != FileOperation.Type.DELETE_FILE) entries.put("payload/" + operation.path(), bytes(operation.content()));
        }
        return zip(entries);
    }

    byte[] updatePackage(UpdateResult result, TemplateStateV2 current, String mode) {
        if (result.kind() != UpdateResult.Kind.CHANGE) throw new ServiceException("PACKAGE_BUILD_FAILED", "update package requires changes", 500);
        Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("protocolVersion", "2");
        metadata.put("packageId", "pkg-" + digest(EngineMapper.stateV2(result.nextTemplateState())).substring(7, 23));
        metadata.put("mode", mode);
        metadata.put("sourceRevision", current.templateRevision());
        metadata.put("currentStateDigest", digest(EngineMapper.stateV2(current)));
        metadata.put("nextStateDigest", digest(EngineMapper.stateV2(result.nextTemplateState())));
        List<Map<String, Object>> strategies = new ArrayList<Map<String, Object>>();
        Map<String, Object> manifest = new LinkedHashMap<String, Object>();
        int index = 0;
        for (ModificationStrategy strategy : result.strategies()) if ("ADD_FILE".equals(strategy.type())) {
            Object capabilityId = strategy.parameters().get("capabilityId");
            Object sourceRef = strategy.parameters().get("sourceRef");
            if (!(capabilityId instanceof String) || !(sourceRef instanceof String)) throw new ServiceException("PACKAGE_BUILD_FAILED", "ADD_FILE source metadata missing", 500);
            Path source = sourceRoot.resolve("capabilities").resolve((String) capabilityId).resolve((String) sourceRef).normalize();
            if (!source.startsWith(sourceRoot) || !Files.isRegularFile(source)) throw new ServiceException("PACKAGE_BUILD_FAILED", "ADD_FILE source missing", 500);
            String payloadRef = "payload/" + strategy.target();
            try { entries.put(payloadRef, Files.readAllBytes(source)); }
            catch (IOException e) { throw new ServiceException("PACKAGE_BUILD_FAILED", "cannot read ADD_FILE source", 500); }
            strategies.add(wireStrategy(strategy, index++, Collections.<String, Object>emptyMap(), payloadRef));
        } else {
            strategies.add(wireStrategy(strategy, index++, strategy.parameters(), null));
        }
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) manifest.put(entry.getKey(), payload(entry.getValue()));
        metadata.put("strategies", strategies);
        List<Map<String, Object>> validation = validation(result);
        validateReconcile(mode, current, result.nextTemplateState(), validation);
        metadata.put("validationPlan", validation);
        metadata.put("payloadManifest", manifest);
        metadata.put("nextTemplateState", EngineMapper.stateV2(result.nextTemplateState()));
        metadata.put("diagnostics", Collections.emptyList());
        entries.put("strategy-update-package.json", jsonBytes(metadata));
        return zip(entries);
    }

    private static Map<String, Object> wireStrategy(ModificationStrategy strategy, int index, Map<String, Object> parameters, String payloadRef) {
        Map<String, Object> wire = new LinkedHashMap<String, Object>();
        wire.put("strategyId", strategy.strategyId()); wire.put("index", index); wire.put("schemaVersion", 1);
        wire.put("type", strategy.type()); wire.put("target", strategy.target()); wire.put("precondition", Collections.emptyMap());
        wire.put("parameters", parameters); wire.put("payloadRef", payloadRef); return wire;
    }
    private static List<Map<String, Object>> validation(UpdateResult result) {
        List<Map<String, Object>> output = new ArrayList<Map<String, Object>>(); int index = 0;
        for (Map<String, Object> value : result.validationPlan().validators()) {
            Map<String, Object> parameters = new LinkedHashMap<String, Object>((Map<String, Object>) value.get("parameters"));
            Object type = parameters.remove("type");
            if (!(type instanceof String)) throw new ServiceException("PACKAGE_BUILD_FAILED", "validator type missing", 500);
            Map<String, Object> item = new LinkedHashMap<String, Object>(); item.put("validatorId", value.get("validatorId")); item.put("index", index++);
            item.put("type", type); item.put("parameters", parameters); output.add(item);
        }
        return output;
    }
    @SuppressWarnings("unchecked") private void validateReconcile(String mode, TemplateStateV2 current,
                                                                      TemplateStateV2 next, List<Map<String, Object>> validation) {
        if (!"RECONCILE".equals(mode)) return;
        if (!digest(EngineMapper.stateV2(current)).equals(digest(EngineMapper.stateV2(next))))
            throw new ServiceException("RECONCILE_STATE_CHANGE_REQUIRED", "RECONCILE must not change TemplateState", 409);
        java.util.Set<String> covered = new java.util.HashSet<String>();
        for (Map<String, Object> item : validation) if ("CAPABILITY_POSTCONDITION".equals(item.get("type"))) {
            Map<String, Object> parameters = (Map<String, Object>) item.get("parameters");
            Object capabilityId = parameters.get("capabilityId"); Object checks = parameters.get("checks");
            if (capabilityId instanceof String && checks instanceof List && !((List<?>) checks).isEmpty()) covered.add((String) capabilityId);
        }
        if (!covered.containsAll(next.effective().keySet())) throw new ServiceException("PACKAGE_BUILD_FAILED", "RECONCILE postconditions missing", 500);
    }
    private Map<String, Object> payload(byte[] bytes) {
        Map<String, Object> result = new LinkedHashMap<String, Object>(); result.put("size", bytes.length); result.put("sha256", digest(bytes)); return result;
    }
    private String digest(Object value) { return digest(jsonBytes(canonical(value))); }
    private static String digest(byte[] bytes) {
        try { byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes); StringBuilder out = new StringBuilder("sha256:"); for (byte value : hash) out.append(String.format("%02x", value & 0xff)); return out.toString(); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    @SuppressWarnings("unchecked") private static Object canonical(Object value) {
        if (value instanceof Map) { java.util.TreeMap<String, Object> sorted = new java.util.TreeMap<String, Object>(); for (Map.Entry<?, ?> item : ((Map<?, ?>) value).entrySet()) sorted.put(String.valueOf(item.getKey()), canonical(item.getValue())); return sorted; }
        if (value instanceof List) { List<Object> result = new ArrayList<Object>(); for (Object item : (List<Object>) value) result.add(canonical(item)); return result; }
        return value;
    }

    private byte[] jsonBytes(Object value) {
        try { return json.writeValueAsBytes(value); }
        catch (IOException e) { throw new ServiceException("PACKAGE_BUILD_FAILED", "cannot serialize package", 500); }
    }
    private byte[] bytes(String content) {
        if (content == null) throw new ServiceException("PACKAGE_BUILD_FAILED", "file operation content missing", 500);
        return content.getBytes(StandardCharsets.UTF_8);
    }
    private byte[] zip(Map<String, byte[]> values) {
        try {
            List<String> paths = new ArrayList<String>(values.keySet()); Collections.sort(paths);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8);
            for (String path : paths) {
                safe(path);
                ZipEntry entry = new ZipEntry(path); entry.setTime(ZIP_TIMESTAMP);
                zip.putNextEntry(entry); zip.write(values.get(path)); zip.closeEntry();
            }
            zip.finish(); zip.close(); return output.toByteArray();
        } catch (IOException e) { throw new ServiceException("PACKAGE_BUILD_FAILED", "cannot build package", 500); }
    }
    private void safe(String path) {
        if (path.startsWith("/") || path.contains("\\") || path.contains("..") || path.isEmpty()) throw new ServiceException("PACKAGE_BUILD_FAILED", "unsafe package path", 500);
    }
}
