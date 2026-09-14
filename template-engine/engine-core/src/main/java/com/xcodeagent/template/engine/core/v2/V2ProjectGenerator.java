package com.xcodeagent.template.engine.core.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.xcodeagent.template.engine.source.TemplateSourceException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/** Materializes a new project solely from V2 source metadata and atomic registry strategies. */
public final class V2ProjectGenerator {
    private final Path root;
    private final TemplateRelease release;
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public V2ProjectGenerator(Path root, TemplateRelease release) {
        this.root = root;
        this.release = release;
    }

    public Map<String, String> generate(TemplateStateV2 state) {
        Map<String, String> files = base();
        List<RankedStrategy> strategies = new ArrayList<RankedStrategy>();
        List<String> capabilityOrder = capabilityOrder(state);
        for (int rank = 0; rank < capabilityOrder.size(); rank++) {
            String capabilityId = capabilityOrder.get(rank);
            CapabilityDefinitionV2 capability = release.capabilities().get(capabilityId);
            if (capability == null) throw new TemplateSourceException("CAPABILITY_UNKNOWN: " + capabilityId);
            for (AdditionDefinition addition : capability.additions()) files.put(addition.target(), capabilitySource(capabilityId, addition.source()));
            for (MigrationDefinition migration : capability.migrations()) files.put(migration.target(), capabilitySource(capabilityId, migration.source()));
            for (ExistingTargetDefinition target : capability.existingTargets()) {
                StrategyDefinition strategy = release.strategies().get(target.strategyId());
                if (strategy == null) throw new TemplateSourceException("CAPABILITY_V2_INVALID: unknown strategy " + target.strategyId());
                strategies.add(new RankedStrategy(rank, strategy));
            }
        }
        Collections.sort(strategies, new Comparator<RankedStrategy>() {
            @Override public int compare(RankedStrategy left, RankedStrategy right) {
                int byCapability = Integer.compare(left.capabilityRank, right.capabilityRank);
                if (byCapability != 0) return byCapability;
                int byOrder = Integer.compare(left.strategy.order(), right.strategy.order());
                return byOrder != 0 ? byOrder : left.strategy.id().compareTo(right.strategy.id());
            }
        });
        render(files, strategies);
        return files;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> base() {
        Map<String, String> result = new LinkedHashMap<String, String>();
        Path base = root.resolve("base");
        try {
            Map<String, Object> manifest = yaml.readValue(base.resolve("base.yaml").toFile(), Map.class);
            Object rawFiles = manifest == null ? null : manifest.get("files");
            if (!(rawFiles instanceof List)) throw new TemplateSourceException("BASE_MANIFEST_INVALID: files");
            for (Object rawFile : (List<?>) rawFiles) {
                if (!(rawFile instanceof Map)) throw new TemplateSourceException("BASE_MANIFEST_INVALID: file entry");
                Map<?, ?> fileEntry = (Map<?, ?>) rawFile;
                Object source = fileEntry.get("source");
                Object target = fileEntry.get("target");
                if (!(source instanceof String) || ((String) source).trim().isEmpty()
                        || !(target instanceof String) || ((String) target).trim().isEmpty())
                    throw new TemplateSourceException("BASE_MANIFEST_INVALID: source/target");
                Path file = base.resolve((String) source).normalize();
                if (!file.startsWith(base) || !Files.isRegularFile(file))
                    throw new TemplateSourceException("BASE_MANIFEST_INVALID: source " + source);
                result.put((String) target, read(file));
            }
            return result;
        } catch (IOException e) { throw failure(e); }
    }

    private String capabilitySource(String capabilityId, String source) {
        Path capabilityRoot = root.resolve("capabilities").resolve(capabilityId).normalize();
        Path file = capabilityRoot.resolve(source).normalize();
        if (!file.startsWith(capabilityRoot) || !Files.isRegularFile(file))
            throw new TemplateSourceException("CAPABILITY_V2_INVALID: source " + source);
        return read(file);
    }

    private List<String> capabilityOrder(TemplateStateV2 state) {
        List<String> result = new ArrayList<String>();
        List<String> ids = new ArrayList<String>(state.effective().keySet());
        Collections.sort(ids);
        Set<String> visited = new HashSet<String>();
        for (String id : ids) orderCapability(id, state, visited, result);
        return result;
    }

    private void orderCapability(String id, TemplateStateV2 state, Set<String> visited, List<String> result) {
        if (!visited.add(id)) return;
        CapabilityDefinitionV2 capability = release.capabilities().get(id);
        if (capability == null) throw new TemplateSourceException("CAPABILITY_UNKNOWN: " + id);
        for (String dependency : capability.requires()) if (state.effective().containsKey(dependency))
            orderCapability(dependency, state, visited, result);
        result.add(id);
    }

    private void render(Map<String, String> files, List<RankedStrategy> strategies) {
        for (RankedStrategy candidate : strategies) {
            StrategyDefinition strategy = candidate.strategy;
            String current = files.get(strategy.target());
            if (current == null) throw new TemplateSourceException("BASE_SURFACE_TARGET_MISSING: " + strategy.target());
            if ("ENSURE_IMPORT".equals(strategy.type()))
                files.put(strategy.target(), ensureImport(current, text(strategy.parameters(), "importStatement")));
            else if ("TEXT_ANCHOR_INSERT".equals(strategy.type()))
                files.put(strategy.target(), managedInsert(current, strategy.parameters()));
            else
                throw new TemplateSourceException("GENERATE_STRATEGY_UNSUPPORTED: " + strategy.type());
        }
    }

    private static String ensureImport(String source, String statement) {
        if (source.contains(statement)) return source;
        int lastImport = source.lastIndexOf("import ");
        if (lastImport < 0) throw new TemplateSourceException("IMPORT_SURFACE_MISSING");
        int end = source.indexOf('\n', lastImport);
        if (end < 0) end = source.length();
        return source.substring(0, end + 1) + statement + "\n" + source.substring(end + 1);
    }

    private static String managedInsert(String source, Map<String, Object> parameters) {
        String anchor = text(parameters, "anchor");
        String marker = text(parameters, "managedMarker");
        String content = text(parameters, "content");
        if (!marker.matches("[a-z0-9][a-z0-9-]*")) throw new TemplateSourceException("MANAGED_BLOCK_INVALID: " + marker);
        String begin = "/* xcodeagent:" + marker + ":begin */";
        String end = "/* xcodeagent:" + marker + ":end */";
        if (count(content, begin) != 1 || count(content, end) != 1 || content.indexOf(begin) > content.indexOf(end))
            throw new TemplateSourceException("MANAGED_BLOCK_INVALID: " + marker);
        int firstBegin = source.indexOf(begin);
        int firstEnd = source.indexOf(end);
        if (firstBegin >= 0 || firstEnd >= 0) {
            if (firstBegin < 0 || firstEnd < firstBegin || count(source, begin) != 1 || count(source, end) != 1)
                throw new TemplateSourceException("MANAGED_BLOCK_INVALID: " + marker);
            String remainder = source.substring(firstEnd + end.length());
            if (remainder.startsWith("\n")) remainder = remainder.substring(1);
            return source.substring(0, firstBegin) + content + remainder;
        }
        int anchorIndex = source.indexOf(anchor);
        if (anchorIndex < 0 || count(source, anchor) != 1) throw new TemplateSourceException("ANCHOR_NOT_UNIQUE: " + anchor);
        String position = parameters.get("position") instanceof String ? (String) parameters.get("position") : "before";
        if (!"before".equals(position) && !"after".equals(position)) throw new TemplateSourceException("ANCHOR_POSITION_INVALID");
        // "before" means before the anchor *line*, not before the first anchor
        // character.  Inserting at anchorIndex leaves the anchor line's indentation
        // in front of the managed block and produces a different authoring shape.
        int insertion = "after".equals(position) ? anchorIndex + anchor.length() : lineStart(source, anchorIndex);
        return source.substring(0, insertion) + content + source.substring(insertion);
    }

    private static int lineStart(String source, int offset) {
        int newline = source.lastIndexOf('\n', offset - 1);
        return newline < 0 ? 0 : newline + 1;
    }

    private static int count(String value, String needle) {
        int count = 0;
        for (int index = value.indexOf(needle); index >= 0; index = value.indexOf(needle, index + needle.length())) count++;
        return count;
    }

    private static String text(Map<String, Object> parameters, String key) {
        Object value = parameters.get(key);
        if (!(value instanceof String) || ((String) value).trim().isEmpty())
            throw new TemplateSourceException("STRATEGY_PARAMETER_INVALID: " + key);
        return (String) value;
    }

    private static String read(Path path) {
        try { return new String(Files.readAllBytes(path), StandardCharsets.UTF_8); }
        catch (IOException e) { throw failure(e); }
    }

    private static TemplateSourceException failure(Exception exception) {
        return new TemplateSourceException("TEMPLATE_SOURCE_INVALID: " + exception.getMessage());
    }

    private static final class RankedStrategy {
        final int capabilityRank;
        final StrategyDefinition strategy;
        RankedStrategy(int capabilityRank, StrategyDefinition strategy) { this.capabilityRank = capabilityRank; this.strategy = strategy; }
    }
}
