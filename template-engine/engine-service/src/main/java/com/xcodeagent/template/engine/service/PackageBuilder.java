package com.xcodeagent.template.engine.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xcodeagent.template.engine.core.v2.ModificationStrategy;
import com.xcodeagent.template.engine.core.v2.StateDigest;
import com.xcodeagent.template.engine.core.v2.TemplateStateV2;
import com.xcodeagent.template.engine.core.v2.UpdateResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private final WireStrategyCompiler strategyCompiler = new WireStrategyCompiler();
    private final ValidatorCompiler validatorCompiler = new ValidatorCompiler();
    PackageBuilder(ObjectMapper json, Path sourceRoot) { this.json = json; this.sourceRoot = sourceRoot.toAbsolutePath().normalize(); }

    byte[] generatedProject(Map<String, String> project, TemplateStateV2 nextTemplateState) {
        Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        for (Map.Entry<String, String> file : project.entrySet()) entries.put(file.getKey(), bytes(file.getValue()));
        entries.put(".xcodeagent/template-state.json", jsonBytes(EngineMapper.stateV2(nextTemplateState)));
        return zip(entries);
    }

    byte[] updatePackage(UpdateResult result, TemplateStateV2 current, String mode) {
        if (result.kind() != UpdateResult.Kind.CHANGE) throw new ServiceException("PACKAGE_BUILD_FAILED", "update package requires changes", 500);
        Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("protocolVersion", "2");
        metadata.put("packageId", "pkg-" + StateDigest.of(EngineMapper.stateV2(result.nextTemplateState())).substring(7, 23));
        metadata.put("mode", mode);
        metadata.put("sourceRevision", current.templateRevision());
        metadata.put("currentStateDigest", StateDigest.of(EngineMapper.stateV2(current)));
        metadata.put("nextStateDigest", StateDigest.of(EngineMapper.stateV2(result.nextTemplateState())));
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
            strategies.add(strategyCompiler.compile(strategy, index++, Collections.<String, Object>emptyMap(), payloadRef));
        } else {
            strategies.add(strategyCompiler.compile(strategy, index++, strategy.parameters(), null));
        }
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) manifest.put(entry.getKey(), payload(entry.getValue()));
        metadata.put("strategies", strategies);
        List<Map<String, Object>> validation = validatorCompiler.compile(result);
        validateReconcile(mode, current, result.nextTemplateState(), validation);
        metadata.put("validationPlan", validation);
        metadata.put("payloadManifest", manifest);
        metadata.put("nextTemplateState", EngineMapper.stateV2(result.nextTemplateState()));
        metadata.put("diagnostics", Collections.emptyList());
        entries.put("strategy-update-package.json", jsonBytes(metadata));
        return zip(entries);
    }

    @SuppressWarnings("unchecked") private void validateReconcile(String mode, TemplateStateV2 current,
                                                                      TemplateStateV2 next, List<Map<String, Object>> validation) {
        if (!"RECONCILE".equals(mode)) return;
        if (!StateDigest.of(EngineMapper.stateV2(current)).equals(StateDigest.of(EngineMapper.stateV2(next))))
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
        Map<String, Object> result = new LinkedHashMap<String, Object>(); result.put("size", bytes.length); result.put("sha256", StateDigest.sha256(bytes)); return result;
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
