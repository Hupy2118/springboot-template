package com.devagentstudio.template.authoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.devagentstudio.template.engine.core.v2.TargetDefinition;
import com.devagentstudio.template.engine.source.StrategyRegistry;
import com.devagentstudio.template.engine.source.TemplateSourceException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Adds the explicit V1 migration contract and generated postcondition to a compilable change draft. */
public final class CapabilityContractPlanner {
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    @SuppressWarnings("unchecked")
    public CapabilityContractDraft plan(Path authoringYaml, Path rawProject, CapabilityDraft capability, StrategyRegistry registry) {
        if (!capability.compilable()) throw new TemplateSourceException("CAPABILITY_DRAFT_UNSUPPORTED");
        Path project = rawProject.toAbsolutePath().normalize();
        Map<String, Object> metadata;
        try { metadata = yaml.readValue(authoringYaml.toFile(), Map.class); }
        catch (IOException e) { throw new TemplateSourceException("AUTHORING_METADATA_INVALID: " + e.getMessage()); }
        require(metadata != null && capability.capabilityId().equals(text(metadata.get("capabilityId"))), "AUTHORING_METADATA_INVALID");
        List<String> requires = requires(metadata.get("requires"));
        List<MigrationDraft> migrations = new ArrayList<MigrationDraft>();
        Object rawMigrations = metadata.get("migrations");
        if (rawMigrations != null) {
            require(rawMigrations instanceof List, "MIGRATION_CONTRACT_INVALID");
            Set<String> ids = new HashSet<String>();
            for (Object value : (List<Object>) rawMigrations) {
                require(value instanceof Map, "MIGRATION_CONTRACT_INVALID");
                Map<String, Object> item = (Map<String, Object>) value;
                String id = text(item.get("id")), source = safePath(item.get("source"));
                String consumer = safePath(item.get("bootstrapConsumerPath")), trigger = text(item.get("executionTrigger"));
                String target = item.containsKey("target") ? safePath(item.get("target")) : consumer;
                Path sourceFile = project.resolve(source).normalize();
                require(ids.add(id) && target.equals(consumer) && sourceFile.startsWith(project) && Files.isRegularFile(sourceFile), "MIGRATION_CONTRACT_INVALID");
                migrations.add(new MigrationDraft(id, source, target, consumer, trigger));
            }
        }
        List<Map<String, Object>> checks = checks(capability, registry);
        require(!checks.isEmpty(), "CAPABILITY_POSTCONDITION_MISSING");
        Map<String, Object> parameters = new LinkedHashMap<String, Object>();
        parameters.put("type", "CAPABILITY_POSTCONDITION"); parameters.put("executionMode", "REAL_WORKSPACE");
        parameters.put("blocking", Boolean.TRUE); parameters.put("timeoutSeconds", Integer.valueOf(30));
        parameters.put("workingDirectory", "."); parameters.put("capabilityId", capability.capabilityId()); parameters.put("checks", checks);
        return new CapabilityContractDraft(capability, requires, migrations,
                Collections.singletonList(new ValidatorDraft(capability.capabilityId() + ".postcondition", parameters)));
    }

    private static List<Map<String, Object>> checks(CapabilityDraft capability, StrategyRegistry registry) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (AdditionDraft addition : capability.additions()) result.add(map("type", "FILE_EXISTS", "path", addition.target()));
        for (StrategyDraft strategy : capability.strategies()) if ("TEXT_ANCHOR_INSERT".equals(strategy.type())) {
            TargetDefinition target = registry.targets().get(strategy.targetId());
            if (target == null) throw new TemplateSourceException("CAPABILITY_POSTCONDITION_MISSING");
            result.add(map("type", "STRUCTURE_CHECK", "path", target.path(), "containsAll",
                    Collections.<Object>singletonList("devagentstudio:" + strategy.parameters().get("managedMarker") + ":begin")));
        }
        return result;
    }

    private static Map<String, Object> map(Object... values) { Map<String, Object> result = new LinkedHashMap<String, Object>(); for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]); return result; }
    @SuppressWarnings("unchecked") private static List<String> requires(Object value) { if (value == null) return Collections.emptyList(); require(value instanceof List, "AUTHORING_METADATA_INVALID"); List<String> result = new ArrayList<String>(); for (Object item : (List<Object>) value) result.add(text(item)); Collections.sort(result); return result; }
    private static String text(Object value) { require(value instanceof String && !((String) value).trim().isEmpty(), "MIGRATION_CONTRACT_INVALID"); return (String) value; }
    private static String safePath(Object value) { String path = text(value); require(!path.startsWith("/") && !path.contains("\\") && !path.contains(".."), "MIGRATION_CONTRACT_INVALID"); return path; }
    private static void require(boolean condition, String message) { if (!condition) throw new TemplateSourceException(message); }
}
