package com.xcodeagent.template.authoring;

import com.xcodeagent.template.engine.core.v2.StrategyDefinition;
import com.xcodeagent.template.engine.core.v2.AnchorDefinition;
import com.xcodeagent.template.engine.core.v2.TargetDefinition;
import com.xcodeagent.template.engine.source.StrategyRegistry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Interprets only V1-safe Workbench changes into compiler drafts. */
public final class CapabilityAnalyzer {
    public CapabilityDraft analyze(String capabilityId, Path baseline, Path project, StrategyRegistry registry) {
        List<AdditionDraft> additions = new ArrayList<AdditionDraft>(); List<StrategyDraft> strategies = new ArrayList<StrategyDraft>(); List<UnsupportedChange> unsupported = new ArrayList<UnsupportedChange>();
        for (FileChange change : new FileDiffer().compare(baseline, project)) {
            if (change.type() == FileChange.Type.ADDED) { additions.add(new AdditionDraft(capabilityId + "." + change.path().replaceAll("[^a-zA-Z0-9]+", "-").replaceAll("-$", ""), change.path(), change.path())); continue; }
            if (change.type() == FileChange.Type.DELETED) { unsupported.add(new UnsupportedChange(change.path(), "DELETE_UNSUPPORTED")); continue; }
            String targetId = targetId(change.path(), registry);
            if (targetId == null) { unsupported.add(new UnsupportedChange(change.path(), "MODIFY_NON_SURFACE")); continue; }
            analyzeSurface(capabilityId, change.path(), targetId, baseline, project, registry, strategies, unsupported);
        }
        Map<String, StrategyDraft> markerOwners = new LinkedHashMap<String, StrategyDraft>();
        for (StrategyDraft strategy : strategies) if ("TEXT_ANCHOR_INSERT".equals(strategy.type())) {
            String marker = (String) strategy.parameters().get("managedMarker");
            StrategyDraft previous = markerOwners.put(marker, strategy);
            if (previous != null && (!previous.targetId().equals(strategy.targetId()) || !previous.anchorKey().equals(strategy.anchorKey())))
                unsupported.add(new UnsupportedChange(strategy.targetId(), "MANAGED_MARKER_COLLISION"));
        }
        return new CapabilityDraft(capabilityId, additions, strategies, unsupported);
    }

    private static void analyzeSurface(String capabilityId, String path, String targetId, Path baseline, Path project, StrategyRegistry registry, List<StrategyDraft> output, List<UnsupportedChange> unsupported) {
        String before = read(baseline.resolve(path)), after = read(project.resolve(path));
        List<String> beforeImports = imports(before), afterImports = imports(after);
        if (!afterImports.containsAll(beforeImports)) { unsupported.add(new UnsupportedChange(path, "IMPORT_REMOVAL")); return; }
        for (String value : afterImports) if (!beforeImports.contains(value)) output.add(new StrategyDraft("ENSURE_IMPORT", targetId, null, map("importStatement", value)));
        before = withoutImports(before); after = withoutImports(after);
        if (before.equals(after)) return;
        List<AnchorDefinition> anchors = anchors(targetId, registry);
        AnchorDefinition match = null; String content = null;
        for (AnchorDefinition strategy : anchors) {
            String anchor = strategy.anchor(); int at = before.indexOf(anchor);
            if (at < 0) continue;
            String prefix = before.substring(0, at), suffix = before.substring(at);
            if (after.startsWith(prefix) && after.endsWith(suffix)) { if (match != null) { unsupported.add(new UnsupportedChange(path, "AMBIGUOUS_ANCHOR_INSERT")); return; } match = strategy; content = after.substring(prefix.length(), after.length() - suffix.length()); }
        }
        if (match == null || content == null || content.isEmpty()) { unsupported.add(new UnsupportedChange(path, "UNEXPLAINED_SURFACE_MODIFICATION")); return; }
        String anchor = match.anchor(); String key = match.anchorKey();
        String marker = ManagedMarker.of(capabilityId, targetId, key);
        String managed = "/* xcodeagent:" + marker + ":begin */\n" + content + (content.endsWith("\n") ? "" : "\n") + "/* xcodeagent:" + marker + ":end */\n";
        output.add(new StrategyDraft("TEXT_ANCHOR_INSERT", targetId, key, map("anchor", anchor, "position", "before", "managedMarker", marker, "content", managed)));
    }
    private static String targetId(String path, StrategyRegistry registry) { for (TargetDefinition target : registry.targets().values()) if (target.path().equals(path)) return target.id(); return null; }
    private static List<AnchorDefinition> anchors(String targetId, StrategyRegistry registry) { List<AnchorDefinition> r = new ArrayList<AnchorDefinition>(); for (AnchorDefinition anchor : registry.anchors().values()) if (targetId.equals(anchor.targetId())) r.add(anchor); return r; }
    private static List<String> imports(String source) { List<String> r = new ArrayList<String>(); for (String line : source.split("(?<=\\n)")) if (line.trim().startsWith("import ")) r.add(line.trim()); return r; }
    private static String withoutImports(String source) { StringBuilder r = new StringBuilder(); for (String line : source.split("(?<=\\n)")) if (!line.trim().startsWith("import ")) r.append(line); return r.toString(); }
    private static String read(Path path) { try { return new String(Files.readAllBytes(path), StandardCharsets.UTF_8); } catch (IOException e) { throw new IllegalStateException(e); } }
    private static Map<String, Object> map(Object... values) { Map<String, Object> r = new LinkedHashMap<String, Object>(); for (int i = 0; i < values.length; i += 2) r.put((String) values[i], values[i + 1]); return r; }
}
