package com.devagentstudio.template.authoring;

import com.devagentstudio.template.engine.core.v2.CapabilityDefinitionV2;
import com.devagentstudio.template.engine.core.v2.CapabilityState;
import com.devagentstudio.template.engine.core.v2.TemplateRelease;
import com.devagentstudio.template.engine.core.v2.TemplateStateV2;
import com.devagentstudio.template.engine.core.v2.V2ProjectGenerator;
import com.devagentstudio.template.engine.source.TemplateSourceException;

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
import java.util.ArrayList;
import java.util.List;

/** Compares V2 generation with the authoring representation of a capability project. */
public final class RoundTripVerifier {
    public void verify(Path sourceRoot, Path project, String capabilityId) {
        TemplateRelease release = new DraftTemplateSourceValidator().validate(sourceRoot);
        Map<String, CapabilityState> effective = new LinkedHashMap<String, CapabilityState>();
        add(capabilityId, release, effective, new LinkedHashSet<String>());
        Map<String, String> generated = new V2ProjectGenerator(sourceRoot, release).generate(new TemplateStateV2(release.revision(), effective, effective, Collections.emptyMap()));
        Map<String, String> expected = files(project);
        Set<String> paths = new TreeSet<String>(); paths.addAll(generated.keySet()); paths.addAll(expected.keySet());
        CapabilityDefinitionV2 capability = release.capabilities().get(capabilityId);
        for (String path : paths) if (!same(generated.get(path), expected.get(path), capability, release, path)) throw new TemplateSourceException("ROUND_TRIP_MISMATCH: " + path);
    }
    private static void add(String id, TemplateRelease release, Map<String, CapabilityState> effective, Set<String> visiting) { if (effective.containsKey(id)) return; if (!visiting.add(id)) throw new TemplateSourceException("CAPABILITY_CYCLE: " + id); CapabilityDefinitionV2 capability = release.capabilities().get(id); if (capability == null) throw new TemplateSourceException("CAPABILITY_UNKNOWN: " + id); for (String requirement : capability.requires()) add(requirement, release, effective, visiting); effective.put(id, new CapabilityState(true, capability.defaultConfig())); visiting.remove(id); }
    private static Map<String, String> files(Path root) { Map<String, String> result = new LinkedHashMap<String, String>(); try { java.util.stream.Stream<Path> stream = Files.walk(root); try { stream.forEach(path -> { if (Files.isRegularFile(path)) try { result.put(root.relativize(path).toString().replace('\\', '/'), new String(Files.readAllBytes(path), StandardCharsets.UTF_8)); } catch (IOException e) { throw new TemplateSourceException("ROUND_TRIP_FAILED: " + e.getMessage()); } }); } finally { stream.close(); } return result; } catch (IOException e) { throw new TemplateSourceException("ROUND_TRIP_FAILED: " + e.getMessage()); } }
    private static boolean same(String generated, String authored, CapabilityDefinitionV2 capability, TemplateRelease release, String path) {
        if (generated == null || authored == null) return false;
        List<String> markers = new ArrayList<String>();
        boolean importSurface = false;
        for (com.devagentstudio.template.engine.core.v2.ExistingTargetDefinition target : capability.existingTargets()) {
            com.devagentstudio.template.engine.core.v2.StrategyDefinition strategy = release.strategies().get(target.strategyId());
            if (strategy == null || !path.equals(strategy.target())) continue;
            if ("ENSURE_IMPORT".equals(strategy.type())) importSurface = true;
            if ("TEXT_ANCHOR_INSERT".equals(strategy.type())) markers.add((String) strategy.parameters().get("managedMarker"));
        }
        if (markers.isEmpty() && !importSurface) return normalize(generated).equals(normalize(authored));
        if (importSurface && !imports(generated).equals(imports(authored))) return false;
        String generatedBody = withoutImports(stripMarkers(generated, markers));
        String authoredBody = withoutImports(stripMarkers(authored, markers));
        return normalize(withoutBlankLines(generatedBody)).equals(normalize(withoutBlankLines(authoredBody)));
    }
    private static Set<String> imports(String source) { Set<String> result = new TreeSet<String>(); for (String line : source.split("(?<=\\n)")) if (line.trim().startsWith("import ")) result.add(line.trim()); return result; }
    private static String withoutImports(String source) { StringBuilder result = new StringBuilder(); for (String line : source.split("(?<=\\n)", -1)) if (!line.trim().startsWith("import ")) result.append(line); return result.toString(); }
    private static String stripMarkers(String source, List<String> markers) { String result = source; for (String marker : markers) result = result.replaceAll("(?m)^[\\t ]*/\\* devagentstudio:" + java.util.regex.Pattern.quote(marker) + ":(?:begin|end) \\*/\\r?\\n?", ""); return result; }
    // Analyzer treats blank lines at an anchor insertion boundary as non-semantic.
    private static String withoutBlankLines(String source) { StringBuilder result = new StringBuilder(); for (String line : source.split("(?<=\\n)", -1)) if (!line.trim().isEmpty()) result.append(line); return result.toString(); }
    private static String normalize(String value) { value = value.replace("\r\n", "\n").replace('\r', '\n'); while (value.endsWith("\n")) value = value.substring(0, value.length() - 1); return value; }
}
