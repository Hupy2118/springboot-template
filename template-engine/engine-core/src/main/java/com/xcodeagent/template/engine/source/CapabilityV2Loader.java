package com.xcodeagent.template.engine.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.xcodeagent.template.engine.core.v2.AdditionDefinition;
import com.xcodeagent.template.engine.core.v2.CapabilityDefinitionV2;
import com.xcodeagent.template.engine.core.v2.ExistingTargetDefinition;
import com.xcodeagent.template.engine.core.v2.MaintainPolicy;
import com.xcodeagent.template.engine.core.v2.TemplateRelease;
import com.xcodeagent.template.engine.core.v2.StrategyDefinition;
import com.xcodeagent.template.engine.core.v2.ValidatorDefinition;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.stream.Stream;

/** Loads the V2 reconcile metadata without changing the legacy source loader. */
public final class CapabilityV2Loader {
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public TemplateRelease load(Path rawRoot) {
        Path root = rawRoot.toAbsolutePath().normalize();
        try {
            String revision = new String(Files.readAllBytes(root.resolve("template-revision.txt")), StandardCharsets.UTF_8).trim();
            require(!revision.isEmpty(), "TEMPLATE_REVISION_MISSING");
            return load(new TemplateSourceContext(root, revision));
        } catch (IOException e) { throw invalid("cannot read template revision", e); }
    }

    public TemplateRelease load(TemplateSourceContext source) {
        Registry registry = registry(source.getRoot());
        Map<String, CapabilityDefinitionV2> capabilities = new LinkedHashMap<String, CapabilityDefinitionV2>();
        Set<String> additionIds = new HashSet<String>();
        Set<String> targets = new HashSet<String>();
        List<String> ids = new ArrayList<String>();
        Path capabilitiesRoot = source.getRoot().resolve("capabilities");
        try {
            java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(capabilitiesRoot);
            try {
                for (Path child : stream) if (Files.isDirectory(child)) ids.add(child.getFileName().toString());
            } finally { stream.close(); }
        } catch (IOException e) { throw invalid("cannot list V2 capabilities", e); }
        Collections.sort(ids);
        for (String id : ids) {
            Path root = capabilitiesRoot.resolve(id);
            Path manifest = root.resolve("capability-v2.yaml");
            if (!Files.exists(manifest)) throw new TemplateSourceException("CAPABILITY_V2_INVALID: missing " + manifest);
            CapabilityDefinitionV2 definition = definition(root, manifest, registry);
            if (!id.equals(definition.id()) || capabilities.put(definition.id(), definition) != null)
                throw new TemplateSourceException("CAPABILITY_V2_INVALID: duplicate or mismatched capability " + id);
            for (AdditionDefinition addition : definition.additions()) {
                if (!additionIds.add(addition.id())) throw new TemplateSourceException("CAPABILITY_V2_INVALID: duplicate additionId " + addition.id());
                if (!targets.add(addition.target())) throw new TemplateSourceException("CAPABILITY_V2_INVALID: duplicate addition target " + addition.target());
            }
        }
        return new TemplateRelease(source.getTemplateRevision(), "sha256:" + sourceDigest(source.getRoot()), capabilities, registry.strategies, registry.validators);
    }

    @SuppressWarnings("unchecked")
    private CapabilityDefinitionV2 definition(Path root, Path manifest, Registry registry) {
        Map<String, Object> map;
        try { map = yaml.readValue(manifest.toFile(), Map.class); }
        catch (IOException e) { throw invalid("cannot parse " + manifest, e); }
        require(map != null && Integer.valueOf(2).equals(number(map.get("schemaVersion"))), "CAPABILITY_V2_INVALID: schemaVersion");
        String id = text(map.get("id"), "id");
        List<String> requires = new ArrayList<String>();
        Object rawRequires = map.get("requires");
        if (rawRequires != null) for (Object value : list(rawRequires, "requires")) {
            if (value instanceof String) requires.add(text(value, "requires"));
            else requires.add(text(object(value, "requires").get("id"), "requires.id"));
        }
        Collections.sort(requires);
        Map<String, Object> config = map.get("defaultConfig") == null ? Collections.<String, Object>emptyMap() : object(map.get("defaultConfig"), "defaultConfig");
        List<ExistingTargetDefinition> existing = new ArrayList<ExistingTargetDefinition>();
        for (Object value : list(map.get("existingTargets"), "existingTargets")) {
            Map<String, Object> item = object(value, "existingTargets entry");
            require(item.size() == 1, "CAPABILITY_V2_INVALID: existingTargets entry");
            StrategyDefinition strategy = registry.strategies.get(text(item.get("strategyId"), "strategyId"));
            require(strategy != null, "CAPABILITY_V2_INVALID: unknown strategyId");
            String target = strategy.target();
            existing.add(new ExistingTargetDefinition(target, strategy.id(), strategy.order()));
        }
        List<AdditionDefinition> additions = new ArrayList<AdditionDefinition>();
        Set<String> additionIds = new HashSet<String>();
        Set<String> additionTargets = new HashSet<String>();
        for (Object value : list(map.get("additions"), "additions")) {
            Map<String, Object> item = object(value, "additions entry");
            String source = path(item.get("source")); String target = path(item.get("target"));
            String additionId = text(item.get("id"), "addition id");
            require(additionIds.add(additionId), "CAPABILITY_V2_INVALID: duplicate additionId " + additionId);
            require(additionTargets.add(target), "CAPABILITY_V2_INVALID: duplicate addition target " + target);
            Path sourcePath = root.resolve(source).normalize();
            require(sourcePath.startsWith(root) && Files.isRegularFile(sourcePath), "CAPABILITY_V2_INVALID: addition source " + source);
            Map<String, Object> policy = object(item.get("maintainPolicy"), "maintainPolicy");
            String mode = text(policy.get("mode"), "maintainPolicy.mode");
            MaintainPolicy maintain;
            if ("NO_OP".equals(mode)) {
                require(!policy.containsKey("strategyId"), "CAPABILITY_V2_INVALID: NO_OP strategyId");
                maintain = new MaintainPolicy(MaintainPolicy.Mode.NO_OP, null, 0);
            } else if ("STRATEGY".equals(mode)) {
                String strategyId = text(policy.get("strategyId"), "maintainPolicy.strategyId");
                StrategyDefinition strategy = registry.strategies.get(strategyId);
                require(strategy != null && target.equals(strategy.target()), "CAPABILITY_V2_INVALID: unknown or mismatched strategyId");
                maintain = new MaintainPolicy(MaintainPolicy.Mode.STRATEGY, strategyId, strategy.order());
            } else throw new TemplateSourceException("CAPABILITY_V2_INVALID: maintainPolicy.mode");
            additions.add(new AdditionDefinition(additionId, source, target, maintain));
        }
        List<Map<String, Object>> validators = new ArrayList<Map<String, Object>>();
        for (Object value : list(map.get("validators"), "validators")) {
            Map<String, Object> item = object(value, "validators entry"); require(item.size() == 1, "CAPABILITY_V2_INVALID: validators entry");
            ValidatorDefinition validator = registry.validators.get(text(item.get("validatorId"), "validatorId"));
            require(validator != null, "CAPABILITY_V2_INVALID: unknown validatorId");
            Map<String, Object> resolved = new LinkedHashMap<String, Object>(); resolved.put("validatorId", validator.id()); resolved.put("order", validator.order()); resolved.put("parameters", validator.parameters()); validators.add(resolved);
        }
        return new CapabilityDefinitionV2(id, requires, config, existing, additions, validators);
    }

    private Registry registry(Path root) {
        Map<String, Object> raw;
        try { raw = yaml.readValue(root.resolve("strategy-registry-v2.yaml").toFile(), Map.class); }
        catch (IOException e) { throw invalid("cannot parse strategy registry", e); }
        require(raw != null && Integer.valueOf(2).equals(number(raw.get("schemaVersion"))), "CAPABILITY_V2_INVALID: strategy registry schemaVersion");
        Map<String, String> targets = new LinkedHashMap<String, String>();
        for (Object value : list(raw.get("targets"), "targets")) { Map<String, Object> target = object(value, "target"); String id = text(target.get("id"), "target.id"); require(targets.put(id, path(target.get("path"))) == null, "CAPABILITY_V2_INVALID: duplicate target"); }
        Map<String, StrategyDefinition> strategies = new LinkedHashMap<String, StrategyDefinition>();
        for (Object value : list(raw.get("strategies"), "strategies")) { Map<String, Object> item = object(value, "strategy"); String id = text(item.get("id"), "strategy.id"); String target = targets.get(text(item.get("targetId"), "strategy.targetId")); require(target != null, "CAPABILITY_V2_INVALID: unknown strategy target"); String type = text(item.get("type"), "strategy.type"); require("RENDER_EXTENSION".equals(type) || "ENSURE_NPM_DEPENDENCY".equals(type) || "TRANSFORM_FILE".equals(type), "CAPABILITY_V2_INVALID: strategy.type"); require(strategies.put(id, new StrategyDefinition(id, target, type, integer(item.get("order"), "strategy.order"), object(item.get("parameters"), "strategy.parameters"))) == null, "CAPABILITY_V2_INVALID: duplicate strategyId"); }
        Map<String, ValidatorDefinition> validators = new LinkedHashMap<String, ValidatorDefinition>();
        for (Object value : list(raw.get("validators"), "validators")) { Map<String, Object> item = object(value, "validator"); String id = text(item.get("id"), "validator.id"); require(validators.put(id, new ValidatorDefinition(id, integer(item.get("order"), "validator.order"), object(item.get("parameters"), "validator.parameters"))) == null, "CAPABILITY_V2_INVALID: duplicate validatorId"); }
        return new Registry(strategies, validators);
    }

    private static final class Registry { final Map<String, StrategyDefinition> strategies; final Map<String, ValidatorDefinition> validators; Registry(Map<String, StrategyDefinition> strategies, Map<String, ValidatorDefinition> validators) { this.strategies = strategies; this.validators = validators; } }

    private static String sourceDigest(Path root) {
        try {
            List<Path> paths = new ArrayList<Path>();
            try (Stream<Path> stream = Files.walk(root)) {
                stream.filter(path -> Files.isRegularFile(path) && !legacyContractPath(root, path)).forEach(paths::add);
            }
            Collections.sort(paths);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Path path : paths) {
                digest.update(root.relativize(path).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Files.readAllBytes(path));
                digest.update((byte) 0);
            }
            byte[] value = digest.digest();
            StringBuilder result = new StringBuilder(); for (byte b : value) result.append(String.format("%02x", b & 0xff)); return result.toString();
        } catch (IOException e) { throw invalid("cannot digest template source", e); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static boolean legacyContractPath(Path root, Path path) {
        String relative = root.relativize(path).toString().replace('\\', '/');
        return "catalog.yaml".equals(relative) || "base/base.yaml".equals(relative)
                || "base/extension-registry.yaml".equals(relative) || relative.endsWith("/capability.yaml");
    }
    private static TemplateSourceException invalid(String message, Exception cause) { return new TemplateSourceException("CAPABILITY_V2_INVALID: " + message + ": " + cause.getMessage()); }
    private static void require(boolean condition, String message) { if (!condition) throw new TemplateSourceException(message); }
    private static String text(Object value, String field) { require(value instanceof String && !((String) value).trim().isEmpty(), "CAPABILITY_V2_INVALID: " + field); return (String) value; }
    private static String path(Object value) { String path = text(value, "path"); require(!path.startsWith("/") && !path.contains("\\") && !path.contains(".."), "CAPABILITY_V2_INVALID: unsafe path"); return path; }
    private static Integer number(Object value) { return value instanceof Number ? Integer.valueOf(((Number) value).intValue()) : null; }
    private static int integer(Object value, String field) { Integer number = number(value); require(number != null, "CAPABILITY_V2_INVALID: " + field); return number.intValue(); }
    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value, String field) { require(value instanceof Map, "CAPABILITY_V2_INVALID: " + field); return new LinkedHashMap<String, Object>((Map<String, Object>) value); }
    @SuppressWarnings("unchecked") private static List<Object> list(Object value, String field) { require(value instanceof List, "CAPABILITY_V2_INVALID: " + field); return (List<Object>) value; }
}
