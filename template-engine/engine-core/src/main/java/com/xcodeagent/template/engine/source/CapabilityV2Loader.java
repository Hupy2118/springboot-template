package com.xcodeagent.template.engine.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.xcodeagent.template.engine.core.v2.AdditionDefinition;
import com.xcodeagent.template.engine.core.v2.CapabilityDefinitionV2;
import com.xcodeagent.template.engine.core.v2.ExistingTargetDefinition;
import com.xcodeagent.template.engine.core.v2.MaintainPolicy;
import com.xcodeagent.template.engine.core.v2.TemplateRelease;

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

/** Loads the V2 reconcile metadata without changing the legacy source loader. */
public final class CapabilityV2Loader {
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public TemplateRelease load(TemplateSourceContext source) {
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
        StringBuilder digestInput = new StringBuilder(source.getTemplateRevision()).append('\n');
        for (String id : ids) {
            Path root = capabilitiesRoot.resolve(id);
            Path manifest = root.resolve("capability-v2.yaml");
            if (!Files.exists(manifest)) throw new TemplateSourceException("CAPABILITY_V2_INVALID: missing " + manifest);
            try { digestInput.append(new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8)); }
            catch (IOException e) { throw invalid("cannot read " + manifest, e); }
            CapabilityDefinitionV2 definition = definition(root, manifest);
            if (!id.equals(definition.id()) || capabilities.put(definition.id(), definition) != null)
                throw new TemplateSourceException("CAPABILITY_V2_INVALID: duplicate or mismatched capability " + id);
            for (AdditionDefinition addition : definition.additions()) {
                if (!additionIds.add(addition.id())) throw new TemplateSourceException("CAPABILITY_V2_INVALID: duplicate additionId " + addition.id());
                if (!targets.add(addition.target())) throw new TemplateSourceException("CAPABILITY_V2_INVALID: duplicate addition target " + addition.target());
            }
        }
        return new TemplateRelease(source.getTemplateRevision(), "sha256:" + sha256(digestInput.toString()), capabilities);
    }

    @SuppressWarnings("unchecked")
    private CapabilityDefinitionV2 definition(Path root, Path manifest) {
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
            existing.add(new ExistingTargetDefinition(path(item.get("path")), text(item.get("strategyId"), "strategyId"), integer(item.get("order"), "order")));
        }
        List<AdditionDefinition> additions = new ArrayList<AdditionDefinition>();
        for (Object value : list(map.get("additions"), "additions")) {
            Map<String, Object> item = object(value, "additions entry");
            String source = path(item.get("source")); String target = path(item.get("target"));
            Path sourcePath = root.resolve(source).normalize();
            require(sourcePath.startsWith(root) && Files.isRegularFile(sourcePath), "CAPABILITY_V2_INVALID: addition source " + source);
            Map<String, Object> policy = object(item.get("maintainPolicy"), "maintainPolicy");
            String mode = text(policy.get("mode"), "maintainPolicy.mode");
            MaintainPolicy maintain;
            if ("NO_OP".equals(mode)) {
                require(!policy.containsKey("strategyId"), "CAPABILITY_V2_INVALID: NO_OP strategyId");
                maintain = new MaintainPolicy(MaintainPolicy.Mode.NO_OP, null, 0);
            } else if ("STRATEGY".equals(mode)) {
                maintain = new MaintainPolicy(MaintainPolicy.Mode.STRATEGY, text(policy.get("strategyId"), "maintainPolicy.strategyId"), integer(policy.get("order"), "maintainPolicy.order"));
            } else throw new TemplateSourceException("CAPABILITY_V2_INVALID: maintainPolicy.mode");
            additions.add(new AdditionDefinition(text(item.get("id"), "addition id"), source, target, maintain));
        }
        List<Map<String, Object>> validators = new ArrayList<Map<String, Object>>();
        for (Object value : list(map.get("validators"), "validators")) validators.add(object(value, "validators entry"));
        return new CapabilityDefinitionV2(id, requires, config, existing, additions, validators);
    }

    private static String sha256(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(); for (byte b : digest) result.append(String.format("%02x", b & 0xff)); return result.toString();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
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
