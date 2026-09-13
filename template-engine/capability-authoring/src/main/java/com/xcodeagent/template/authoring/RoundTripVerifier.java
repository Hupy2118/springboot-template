package com.xcodeagent.template.authoring;

import com.xcodeagent.template.engine.core.v2.CapabilityDefinitionV2;
import com.xcodeagent.template.engine.core.v2.CapabilityState;
import com.xcodeagent.template.engine.core.v2.TemplateRelease;
import com.xcodeagent.template.engine.core.v2.TemplateStateV2;
import com.xcodeagent.template.engine.core.v2.V2ProjectGenerator;
import com.xcodeagent.template.engine.source.TemplateSourceException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Compares V2 generation with an authoring project, allowing only line-ending and EOF normalization. */
public final class RoundTripVerifier {
    public void verify(Path sourceRoot, Path project, String capabilityId) {
        TemplateRelease release = new DraftTemplateSourceValidator().validate(sourceRoot);
        Map<String, CapabilityState> effective = new LinkedHashMap<String, CapabilityState>();
        add(capabilityId, release, effective, new LinkedHashSet<String>());
        Map<String, String> generated = new V2ProjectGenerator(sourceRoot, release).generate(new TemplateStateV2(release.revision(), effective, effective, Collections.emptyMap()));
        Map<String, String> expected = files(project);
        Set<String> paths = new TreeSet<String>(); paths.addAll(generated.keySet()); paths.addAll(expected.keySet());
        for (String path : paths) if (!same(generated.get(path), expected.get(path))) throw new TemplateSourceException("ROUND_TRIP_MISMATCH: " + path);
    }
    private static void add(String id, TemplateRelease release, Map<String, CapabilityState> effective, Set<String> visiting) { if (effective.containsKey(id)) return; if (!visiting.add(id)) throw new TemplateSourceException("CAPABILITY_CYCLE: " + id); CapabilityDefinitionV2 capability = release.capabilities().get(id); if (capability == null) throw new TemplateSourceException("CAPABILITY_UNKNOWN: " + id); for (String requirement : capability.requires()) add(requirement, release, effective, visiting); effective.put(id, new CapabilityState(true, capability.defaultConfig())); visiting.remove(id); }
    private static Map<String, String> files(Path root) { Map<String, String> result = new LinkedHashMap<String, String>(); try { java.util.stream.Stream<Path> stream = Files.walk(root); try { stream.forEach(path -> { if (Files.isRegularFile(path)) try { result.put(root.relativize(path).toString().replace('\\', '/'), new String(Files.readAllBytes(path), StandardCharsets.UTF_8)); } catch (IOException e) { throw new TemplateSourceException("ROUND_TRIP_FAILED: " + e.getMessage()); } }); } finally { stream.close(); } return result; } catch (IOException e) { throw new TemplateSourceException("ROUND_TRIP_FAILED: " + e.getMessage()); } }
    private static boolean same(String left, String right) { return left != null && right != null && normalize(left).equals(normalize(right)); }
    private static String normalize(String value) { value = value.replace("\r\n", "\n").replace('\r', '\n'); while (value.endsWith("\n")) value = value.substring(0, value.length() - 1); return value; }
}
