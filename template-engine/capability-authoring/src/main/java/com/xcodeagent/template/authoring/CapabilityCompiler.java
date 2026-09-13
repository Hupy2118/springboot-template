package com.xcodeagent.template.authoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.xcodeagent.template.engine.source.TemplateSourceException;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Materializes one compiler draft as V2 atomic entries through a staging-tree swap. */
public final class CapabilityCompiler {
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    @SuppressWarnings("unchecked")
    public void compile(Path rawSourceRoot, Path rawProject, CapabilityContractDraft draft) {
        Path root = rawSourceRoot.toAbsolutePath().normalize(), project = rawProject.toAbsolutePath().normalize();
        String capabilityId = draft.capability().capabilityId();
        Path staging;
        try { staging = Files.createTempDirectory(root.getParent(), ".capability-compile-"); copy(root, staging); }
        catch (IOException e) { throw new TemplateSourceException("CAPABILITY_COMPILE_FAILED: " + e.getMessage()); }
        try {
            Path capabilityRoot = staging.resolve("capabilities").resolve(capabilityId);
            require(!Files.exists(capabilityRoot), "CAPABILITY_ALREADY_EXISTS: " + capabilityId);
            Files.createDirectories(capabilityRoot);
            Map<String, Object> registry = yaml.readValue(staging.resolve("strategy-registry-v2.yaml").toFile(), Map.class);
            List<Object> strategies = (List<Object>) registry.get("strategies"), validators = (List<Object>) registry.get("validators");
            int order = maxOrder(strategies);
            List<String> strategyIds = new ArrayList<String>();
            for (StrategyDraft strategy : draft.capability().strategies()) {
                String id = strategyId(capabilityId, strategy); require(!strategyIds.contains(id), "STRATEGY_ID_COLLISION: " + id); strategyIds.add(id);
                Map<String, Object> item = new LinkedHashMap<String, Object>(); item.put("id", id); item.put("targetId", strategy.targetId()); item.put("type", strategy.type()); item.put("order", Integer.valueOf(order += 10)); item.put("parameters", strategy.parameters()); strategies.add(item);
            }
            int validatorOrder = maxOrder(validators);
            List<String> validatorIds = new ArrayList<String>();
            for (ValidatorDraft validator : draft.validators()) { require(!validatorIds.contains(validator.id()), "VALIDATOR_ID_COLLISION: " + validator.id()); validatorIds.add(validator.id()); Map<String, Object> item = new LinkedHashMap<String, Object>(); item.put("id", validator.id()); item.put("order", Integer.valueOf(validatorOrder += 10)); item.put("parameters", validator.parameters()); validators.add(item); }
            yaml.writeValue(staging.resolve("strategy-registry-v2.yaml").toFile(), registry);
            Map<String, Object> manifest = new LinkedHashMap<String, Object>(); manifest.put("id", capabilityId); manifest.put("schemaVersion", Integer.valueOf(2)); manifest.put("defaultConfig", Collections.emptyMap()); manifest.put("requires", draft.requires());
            List<Object> existing = new ArrayList<Object>(); for (String id : strategyIds) existing.add(single("strategyId", id)); manifest.put("existingTargets", existing);
            List<Object> additions = new ArrayList<Object>(); for (AdditionDraft addition : draft.capability().additions()) { copyFile(project, addition.source(), capabilityRoot.resolve(addition.source())); Map<String, Object> item = new LinkedHashMap<String, Object>(); item.put("id", addition.id()); item.put("source", addition.source()); item.put("target", addition.target()); item.put("maintainPolicy", single("mode", "NO_OP")); additions.add(item); } manifest.put("additions", additions);
            List<Object> migrations = new ArrayList<Object>(); for (MigrationDraft migration : draft.migrations()) { copyFile(project, migration.source(), capabilityRoot.resolve(migration.source())); Map<String, Object> item = new LinkedHashMap<String, Object>(); item.put("id", migration.id()); item.put("source", migration.source()); item.put("target", migration.target()); item.put("bootstrapConsumerPath", migration.bootstrapConsumerPath()); item.put("executionTrigger", migration.executionTrigger()); migrations.add(item); } if (!migrations.isEmpty()) manifest.put("migrations", migrations);
            List<Object> validatorRefs = new ArrayList<Object>(); for (String id : validatorIds) validatorRefs.add(single("validatorId", id)); manifest.put("validators", validatorRefs); yaml.writeValue(capabilityRoot.resolve("capability-v2.yaml").toFile(), manifest);
            replace(root, staging);
        } catch (IOException e) { delete(staging); throw new TemplateSourceException("CAPABILITY_COMPILE_FAILED: " + e.getMessage()); }
        catch (RuntimeException e) { delete(staging); throw e; }
    }
    @SuppressWarnings("unchecked") private static int maxOrder(List<Object> values) { int result = 0; for (Object value : values) { Object order = ((Map<String, Object>) value).get("order"); if (order instanceof Number) result = Math.max(result, ((Number) order).intValue()); } return result; }
    private static String strategyId(String cap, StrategyDraft draft) { String suffix = draft.anchorKey() == null ? "import-" + ((String) draft.parameters().get("importStatement")).replaceAll("[^a-zA-Z0-9]+", "-").replaceAll("-$", "").toLowerCase() : draft.anchorKey(); return cap + "." + draft.targetId().replace('.', '-') + "." + suffix; }
    private static Map<String, Object> single(String key, Object value) { Map<String, Object> result = new LinkedHashMap<String, Object>(); result.put(key, value); return result; }
    private static void copyFile(Path project, String source, Path target) throws IOException { Path from = project.resolve(source).normalize(); require(from.startsWith(project) && Files.isRegularFile(from), "CAPABILITY_SOURCE_MISSING: " + source); Files.createDirectories(target.getParent()); Files.copy(from, target, StandardCopyOption.REPLACE_EXISTING); }
    private static void copy(Path from, Path to) throws IOException { java.util.stream.Stream<Path> paths = Files.walk(from); try { paths.forEach(path -> { try { Path target = to.resolve(from.relativize(path).toString()); if (Files.isDirectory(path)) Files.createDirectories(target); else Files.copy(path, target, StandardCopyOption.COPY_ATTRIBUTES); } catch (IOException e) { throw new TemplateSourceException("CAPABILITY_COMPILE_FAILED: " + e.getMessage()); } }); } finally { paths.close(); } }
    private static void replace(Path root, Path staging) throws IOException { Path backup = root.resolveSibling("." + root.getFileName() + ".backup"); Files.move(root, backup, StandardCopyOption.ATOMIC_MOVE); try { Files.move(staging, root, StandardCopyOption.ATOMIC_MOVE); delete(backup); } catch (AtomicMoveNotSupportedException e) { Files.move(staging, root); delete(backup); } catch (IOException e) { Files.move(backup, root); throw e; } }
    private static void delete(Path root) { try { java.util.stream.Stream<Path> paths = Files.walk(root); try { paths.sorted(Collections.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) { } }); } finally { paths.close(); } } catch (IOException ignored) { } }
    private static void require(boolean ok, String message) { if (!ok) throw new TemplateSourceException(message); }
}
