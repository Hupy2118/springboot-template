package com.devagentstudio.template.engine.service;

import com.devagentstudio.template.engine.core.v2.StateDigest;
import com.devagentstudio.template.engine.core.v3.CodeTemplateRelease;
import com.devagentstudio.template.engine.core.v3.V3Exception;
import com.devagentstudio.template.engine.core.v3.V3UpdateResult;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds the V3 generate and update ZIPs without touching a caller Workspace. */
final class NextPackageBuilder {
    private static final long ZIP_TIMESTAMP = 0L;
    private final ObjectMapper json;
    private final CodeTemplateRelease release;
    NextPackageBuilder(ObjectMapper json, CodeTemplateRelease release) { this.json = json; this.release = release; }

    byte[] generatedProject(Map<String, byte[]> project, Map<String, Object> state) {
        Map<String, byte[]> entries = new TreeMap<String, byte[]>(project);
        entries.put(".devagentstudio/template-state.json", jsonBytes(state));
        return zip(entries);
    }

    byte[] updatePackage(V3UpdateResult result) {
        if (result.kind() != V3UpdateResult.Kind.CHANGE) throw packageError("update package requires changes");
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        String currentDigest = StateDigest.of(result.currentState());
        String nextDigest = StateDigest.of(result.nextState());
        metadata.put("protocolVersion", "3"); metadata.put("packageId", "pkg-" + nextDigest.substring(7, 23));
        metadata.put("mode", result.mode()); metadata.put("sourceRevision", release.revision());
        metadata.put("currentStateDigest", currentDigest); metadata.put("nextStateDigest", nextDigest);
        metadata.put("operations", result.operations()); metadata.put("validationPlan", Collections.emptyList());
        Map<String, Object> manifest = new TreeMap<String, Object>();
        Map<String, byte[]> entries = new TreeMap<String, byte[]>();
        for (Map.Entry<String, byte[]> payload : result.payloads().entrySet()) {
            if (!payload.getKey().startsWith("payload/") || !safe(payload.getKey())) throw packageError("unsafe payload path");
            entries.put(payload.getKey(), payload.getValue());
            Map<String, Object> value = new LinkedHashMap<String, Object>();
            value.put("size", payload.getValue().length); value.put("sha256", StateDigest.sha256(payload.getValue()));
            manifest.put(payload.getKey(), value);
        }
        metadata.put("payloadManifest", manifest); metadata.put("nextTemplateState", result.nextState()); metadata.put("diagnostics", Collections.emptyList());
        entries.put("extension-update-package.json", jsonBytes(metadata));
        return zip(entries);
    }

    private byte[] jsonBytes(Object value) {
        try { return json.writeValueAsBytes(value); }
        catch (IOException e) { throw packageError("cannot serialize V3 package"); }
    }
    private byte[] zip(Map<String, byte[]> values) {
        try {
            List<String> paths = new ArrayList<String>(values.keySet()); Collections.sort(paths);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8);
            for (String path : paths) {
                if (!safe(path)) throw packageError("unsafe V3 package path " + path);
                ZipEntry entry = new ZipEntry(path); entry.setTime(ZIP_TIMESTAMP);
                zip.putNextEntry(entry); zip.write(values.get(path)); zip.closeEntry();
            }
            zip.finish(); zip.close(); return output.toByteArray();
        } catch (IOException e) { throw packageError("cannot build V3 package"); }
    }
    private boolean safe(String path) {
        if (path == null || path.isEmpty() || path.startsWith("/") || path.contains("\\")) return false;
        for (String part : path.split("/")) if (part.isEmpty() || ".".equals(part) || "..".equals(part)) return false;
        return true;
    }
    private V3Exception packageError(String message) { return new V3Exception("PACKAGE_BUILD_FAILED", message, 500); }
}
