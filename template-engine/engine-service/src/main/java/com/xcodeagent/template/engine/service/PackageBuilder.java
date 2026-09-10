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

    byte[] updatePackage(UpdateResult result) {
        if (result.kind() != UpdateResult.Kind.CHANGE) throw new ServiceException("PACKAGE_BUILD_FAILED", "update package requires changes", 500);
        Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        entries.put("manifest.json", jsonBytes(EngineMapper.updateManifest(result)));
        entries.put("modification-strategy.json", jsonBytes(EngineMapper.strategies(result)));
        entries.put("next-template-state.json", jsonBytes(EngineMapper.stateV2(result.nextTemplateState())));
        entries.put("validation-plan.json", jsonBytes(EngineMapper.validationPlan(result)));
        for (ModificationStrategy strategy : result.strategies()) if ("ADD_FILE".equals(strategy.type())) {
            Object capabilityId = strategy.parameters().get("capabilityId");
            Object sourceRef = strategy.parameters().get("sourceRef");
            if (!(capabilityId instanceof String) || !(sourceRef instanceof String)) throw new ServiceException("PACKAGE_BUILD_FAILED", "ADD_FILE source metadata missing", 500);
            Path source = sourceRoot.resolve("capabilities").resolve((String) capabilityId).resolve((String) sourceRef).normalize();
            if (!source.startsWith(sourceRoot) || !Files.isRegularFile(source)) throw new ServiceException("PACKAGE_BUILD_FAILED", "ADD_FILE source missing", 500);
            try { entries.put("payload/" + strategy.target(), Files.readAllBytes(source)); }
            catch (IOException e) { throw new ServiceException("PACKAGE_BUILD_FAILED", "cannot read ADD_FILE source", 500); }
        }
        return zip(entries);
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
