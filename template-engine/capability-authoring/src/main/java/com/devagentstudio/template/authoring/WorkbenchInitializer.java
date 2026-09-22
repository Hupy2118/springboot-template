package com.devagentstudio.template.authoring;

import com.devagentstudio.template.engine.core.v2.CapabilityDefinitionV2;
import com.devagentstudio.template.engine.core.v2.CapabilityState;
import com.devagentstudio.template.engine.core.v2.TemplateRelease;
import com.devagentstudio.template.engine.core.v2.TemplateStateV2;
import com.devagentstudio.template.engine.core.v2.V2ProjectGenerator;
import com.devagentstudio.template.engine.source.CapabilityV2Loader;
import com.devagentstudio.template.engine.source.TemplateSourceException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Creates an isolated authoring workbench from the published Base and required capabilities. */
public final class WorkbenchInitializer {
    private final Path sourceRoot;
    private final Path workbenchesRoot;

    public WorkbenchInitializer(Path rawSourceRoot, Path rawWorkbenchesRoot) {
        this.sourceRoot = rawSourceRoot.toAbsolutePath().normalize();
        this.workbenchesRoot = rawWorkbenchesRoot.toAbsolutePath().normalize();
    }

    public Path initialize(String capabilityId, List<String> requestedRequires) {
        requireCapabilityId(capabilityId);
        Path workbench = workbenchesRoot.resolve(capabilityId).normalize();
        if (!workbench.startsWith(workbenchesRoot)) throw new TemplateSourceException("WORKBENCH_PATH_INVALID");
        if (Files.exists(workbench)) throw new TemplateSourceException("WORKBENCH_ALREADY_EXISTS: " + capabilityId);

        TemplateRelease release = new CapabilityV2Loader().load(sourceRoot);
        if (release.capabilities().containsKey(capabilityId)) throw new TemplateSourceException("CAPABILITY_ID_ALREADY_PUBLISHED: " + capabilityId);
        List<String> requires = normalizedRequires(capabilityId, requestedRequires, release);
        Map<String, CapabilityState> effective = effectiveCapabilities(requires, release);
        Map<String, String> baseline = new V2ProjectGenerator(sourceRoot, release).generate(
                new TemplateStateV2(release.revision(), effective, effective, Collections.<String, com.devagentstudio.template.engine.core.v2.AppliedAdditionState>emptyMap()));

        try {
            Files.createDirectories(workbenchesRoot);
            Path staging = Files.createTempDirectory(workbenchesRoot, "." + capabilityId + "-");
            try {
                writeFiles(staging.resolve("baseline"), baseline);
                writeFiles(staging.resolve("project"), baseline);
                Files.write(staging.resolve("authoring.yaml"), authoringYaml(capabilityId, requires, release.revision()).getBytes(StandardCharsets.UTF_8));
                move(staging, workbench);
                return workbench;
            } catch (IOException e) {
                delete(staging);
                throw e;
            }
        } catch (IOException e) {
            throw new TemplateSourceException("WORKBENCH_INIT_FAILED: " + e.getMessage());
        }
    }

    private static List<String> normalizedRequires(String capabilityId, List<String> values, TemplateRelease release) {
        Set<String> unique = new LinkedHashSet<String>();
        if (values != null) for (String value : values) {
            if (value == null || value.trim().isEmpty()) throw new TemplateSourceException("CAPABILITY_REQUIRE_INVALID");
            if (capabilityId.equals(value) || !release.capabilities().containsKey(value))
                throw new TemplateSourceException("CAPABILITY_REQUIRE_INVALID: " + value);
            unique.add(value);
        }
        List<String> result = new ArrayList<String>(unique);
        Collections.sort(result);
        return result;
    }

    private static Map<String, CapabilityState> effectiveCapabilities(List<String> requires, TemplateRelease release) {
        Map<String, CapabilityState> result = new LinkedHashMap<String, CapabilityState>();
        Set<String> visiting = new LinkedHashSet<String>();
        for (String id : requires) resolve(id, release, result, visiting);
        return result;
    }

    private static void resolve(String id, TemplateRelease release, Map<String, CapabilityState> result, Set<String> visiting) {
        if (result.containsKey(id)) return;
        if (!visiting.add(id)) throw new TemplateSourceException("CAPABILITY_CYCLE: " + id);
        CapabilityDefinitionV2 definition = release.capabilities().get(id);
        if (definition == null) throw new TemplateSourceException("CAPABILITY_UNKNOWN: " + id);
        for (String dependency : definition.requires()) resolve(dependency, release, result, visiting);
        result.put(id, new CapabilityState(true, definition.defaultConfig()));
        visiting.remove(id);
    }

    private static void writeFiles(Path root, Map<String, String> files) throws IOException {
        List<String> paths = new ArrayList<String>(files.keySet());
        Collections.sort(paths);
        for (String relative : paths) {
            if (relative.startsWith("/") || relative.contains("\\") || relative.contains("..")) throw new TemplateSourceException("WORKBENCH_PATH_INVALID: " + relative);
            Path file = root.resolve(relative).normalize();
            if (!file.startsWith(root)) throw new TemplateSourceException("WORKBENCH_PATH_INVALID: " + relative);
            Files.createDirectories(file.getParent());
            Files.write(file, files.get(relative).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String authoringYaml(String capabilityId, List<String> requires, String revision) {
        StringBuilder value = new StringBuilder();
        value.append("capabilityId: ").append(capabilityId).append('\n');
        value.append("requires:\n");
        for (String dependency : requires) value.append("  - ").append(dependency).append('\n');
        value.append("templateRevision: ").append(revision).append('\n');
        return value.toString();
    }

    private static void move(Path staging, Path target) throws IOException {
        try { Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(staging, target); }
    }

    private static void delete(Path root) {
        try {
            java.util.stream.Stream<Path> paths = Files.walk(root);
            try { paths.sorted(Collections.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) { } }); }
            finally { paths.close(); }
        } catch (IOException ignored) { }
    }

    private static void requireCapabilityId(String value) {
        if (value == null || !value.matches("[a-z0-9][a-z0-9-]*")) throw new TemplateSourceException("CAPABILITY_ID_INVALID");
    }
}
