package com.xcodeagent.template.engine.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xcodeagent.template.engine.core.CorePlanResult;
import com.xcodeagent.template.engine.core.FileOperation;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
    PackageBuilder(ObjectMapper json) { this.json = json; }

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

    byte[] updatePackage(CorePlanResult plan) {
        Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        entries.put("change-set.json", jsonBytes(EngineMapper.changeSet(plan)));
        entries.put("next-template-state.json", jsonBytes(EngineMapper.state(plan.nextTemplateState())));
        for (FileOperation operation : plan.operations()) {
            if (operation.type() != FileOperation.Type.DELETE_FILE) entries.put("payload/" + operation.path(), bytes(operation.content()));
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
