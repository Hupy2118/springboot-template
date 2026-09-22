package com.devagentstudio.template.authoring;

import com.devagentstudio.template.engine.core.v2.StrategyDefinition;
import com.devagentstudio.template.engine.core.v2.AnchorDefinition;
import com.devagentstudio.template.engine.core.v2.TargetDefinition;
import com.devagentstudio.template.engine.source.StrategyRegistry;

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
            String candidate = anchorInsertContent(before, after, strategy);
            if (candidate != null) { if (match != null) { unsupported.add(new UnsupportedChange(path, "AMBIGUOUS_ANCHOR_INSERT")); return; } match = strategy; content = candidate; }
        }
        if (match == null || content == null || content.isEmpty()) { unsupported.add(new UnsupportedChange(path, "UNEXPLAINED_SURFACE_MODIFICATION")); return; }
        String anchor = match.anchor(); String key = match.anchorKey();
        String marker = ManagedMarker.of(capabilityId, targetId, key);
        String managed = "/* devagentstudio:" + marker + ":begin */\n" + content + (content.endsWith("\n") ? "" : "\n") + "/* devagentstudio:" + marker + ":end */\n";
        output.add(new StrategyDraft("TEXT_ANCHOR_INSERT", targetId, key, map("anchor", anchor, "position", "before", "managedMarker", marker, "content", managed)));
    }
    /**
     * Identifies an insertion by its registered anchor boundary, rather than by the
     * complete text surrounding that insertion.  The anchor and everything after it
     * are immutable; blank lines immediately adjacent to the inserted block are not.
     */
    private static String anchorInsertContent(String before, String after, AnchorDefinition strategy) {
        if (!"before".equals(strategy.position())) return null;
        String anchor = strategy.anchor();
        if (occurrences(before, anchor) != 1 || occurrences(after, anchor) != 1) return null;
        int beforeAnchor = before.indexOf(anchor), afterAnchor = after.indexOf(anchor);
        int beforeAnchorLine = lineStart(before, beforeAnchor), afterAnchorLine = lineStart(after, afterAnchor);
        if (!before.substring(beforeAnchorLine).equals(after.substring(afterAnchorLine))) return null;

        String stablePrefix = withoutTrailingBlankLines(before.substring(0, beforeAnchorLine));
        String insertionArea = after.substring(0, afterAnchorLine);
        if (!insertionArea.startsWith(stablePrefix)) return null;
        String inserted = withoutBoundaryBlankLines(insertionArea.substring(stablePrefix.length()));
        return inserted.isEmpty() ? null : inserted;
    }

    private static int occurrences(String source, String value) {
        int result = 0, offset = 0;
        while ((offset = source.indexOf(value, offset)) >= 0) { result++; offset += value.length(); }
        return result;
    }

    private static String withoutTrailingBlankLines(String source) {
        int offset = 0, end = 0;
        for (String line : lines(source)) { offset += line.length(); if (!line.trim().isEmpty()) end = offset; }
        return source.substring(0, end);
    }

    private static String withoutBoundaryBlankLines(String source) {
        int offset = 0, start = -1, end = -1;
        for (String line : lines(source)) {
            int lineEnd = offset + line.length();
            if (!line.trim().isEmpty()) { if (start < 0) start = offset; end = lineEnd; }
            offset = lineEnd;
        }
        return start < 0 ? "" : source.substring(start, end);
    }

    private static int lineStart(String source, int offset) { int newline = source.lastIndexOf('\n', offset - 1); return newline < 0 ? 0 : newline + 1; }
    private static String[] lines(String source) { return source.split("(?<=\\n)", -1); }
    private static String targetId(String path, StrategyRegistry registry) { for (TargetDefinition target : registry.targets().values()) if (target.path().equals(path)) return target.id(); return null; }
    private static List<AnchorDefinition> anchors(String targetId, StrategyRegistry registry) { List<AnchorDefinition> r = new ArrayList<AnchorDefinition>(); for (AnchorDefinition anchor : registry.anchors().values()) if (targetId.equals(anchor.targetId())) r.add(anchor); return r; }
    private static List<String> imports(String source) { List<String> r = new ArrayList<String>(); for (String line : source.split("(?<=\\n)")) if (line.trim().startsWith("import ")) r.add(line.trim()); return r; }
    private static String withoutImports(String source) { StringBuilder r = new StringBuilder(); for (String line : source.split("(?<=\\n)")) if (!line.trim().startsWith("import ")) r.append(line); return r.toString(); }
    private static String read(Path path) { try { return new String(Files.readAllBytes(path), StandardCharsets.UTF_8); } catch (IOException e) { throw new IllegalStateException(e); } }
    private static Map<String, Object> map(Object... values) { Map<String, Object> r = new LinkedHashMap<String, Object>(); for (int i = 0; i < values.length; i += 2) r.put((String) values[i], values[i + 1]); return r; }
}
