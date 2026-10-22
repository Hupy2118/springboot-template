package com.cmbchina.template.backend.maintenance;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Deterministic YAML projection and ownership helpers for Base + Extension configuration. */
final class YamlContributionCompiler {
    private YamlContributionCompiler() { }

    static Map<String,Object> read(Path file) {
        try {
            Object value = new Yaml().load(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            if (!(value instanceof Map)) throw new TemplateException("APPLICATION_YAML_INVALID", file + " root must be an object");
            return copy((Map<String,Object>) value);
        } catch (TemplateException e) { throw e; }
        catch (Exception e) { throw new TemplateException("APPLICATION_YAML_INVALID", e.getMessage()); }
    }

    static void write(Path file, Map<String,Object> value) {
        try {
            Files.createDirectories(file.getParent());
            DumperOptions options = new DumperOptions(); options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK); options.setPrettyFlow(false);
            Files.write(file, new Yaml(options).dump(value).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { throw new TemplateException("APPLICATION_YAML_INVALID", e.getMessage()); }
    }

    static Map<String,Object> project(Map<String,Object> base, Collection<Map<String,Object>> contributions) {
        Map<String,Object> result = copy(base);
        for (Map<String,Object> contribution : contributions) merge(result, contribution, "");
        return result;
    }

    static void merge(Map<String,Object> target, Map<String,Object> source, String prefix) {
        for (Map.Entry<String,Object> entry : source.entrySet()) {
            String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object incoming = entry.getValue(); Object existing = target.get(entry.getKey());
            if (incoming instanceof Map) {
                if (existing == null) target.put(entry.getKey(), copy((Map<String,Object>) incoming));
                else if (existing instanceof Map) merge((Map<String,Object>) existing, (Map<String,Object>) incoming, path);
                else throw new TemplateException("APPLICATION_YAML_CONFLICT", path);
            } else if (existing == null) target.put(entry.getKey(), copyValue(incoming));
            else if (!existing.equals(incoming)) throw new TemplateException("APPLICATION_YAML_CONFLICT", path);
        }
    }

    /** Removes known Extension leaves after verifying that their Workspace values are intact. */
    static Map<String,Object> strip(Map<String,Object> workspace, Collection<Map<String,Object>> contributions) {
        Map<String,Object> result = copy(workspace);
        for (Map<String,Object> contribution : contributions) strip(result, contribution, "");
        return result;
    }

    @SuppressWarnings("unchecked") private static void strip(Map<String,Object> target, Map<String,Object> contribution, String prefix) {
        for (Map.Entry<String,Object> entry : contribution.entrySet()) {
            String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object actual = target.get(entry.getKey()); Object expected = entry.getValue();
            if (expected instanceof Map) {
                if (!(actual instanceof Map)) throw new TemplateException("GENERATED_CONTRIBUTION_MODIFIED", path);
                strip((Map<String,Object>) actual, (Map<String,Object>) expected, path);
                if (((Map<String,Object>) actual).isEmpty()) target.remove(entry.getKey());
            } else {
                if (!Objects.equals(actual, expected)) throw new TemplateException("GENERATED_CONTRIBUTION_MODIFIED", path);
                target.remove(entry.getKey());
            }
        }
    }

    /** Updates an owner's known leaves and assigns newly added leaves to the current edit target. */
    static Map<String,Object> synchronizeContribution(Map<String,Object> workspace, Map<String,Object> base,
                                                        Map<String,Object> contribution, Set<String> ownedLeaves,
                                                        boolean ownNewLeaves) {
        Map<String,Object> result = copy(contribution);
        for (Leaf leaf : leafValues(workspace)) {
            if (ownedLeaves.contains(leaf.path) || (ownNewLeaves && !containsPath(base, leaf.path) && !ownedLeaves.contains(leaf.path)))
                putPath(result, leaf.path, leaf.value);
        }
        for (String path : new ArrayList<String>(ownedLeaves)) if (!containsPath(workspace, path)) removePath(result, path);
        return result;
    }

    static Set<String> leaves(Map<String,Object> value) {
        Set<String> result = new TreeSet<String>(); for (Leaf leaf : leafValues(value)) result.add(leaf.path); return result;
    }
    private static List<Leaf> leafValues(Map<String,Object> value) { List<Leaf> result = new ArrayList<Leaf>(); leaves(value, "", result); return result; }
    @SuppressWarnings("unchecked") private static void leaves(Map<String,Object> value, String prefix, List<Leaf> output) {
        for (Map.Entry<String,Object> entry : value.entrySet()) {
            String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            if (entry.getValue() instanceof Map) leaves((Map<String,Object>) entry.getValue(), path, output);
            else output.add(new Leaf(path, copyValue(entry.getValue())));
        }
    }
    @SuppressWarnings("unchecked") static boolean containsPath(Map<String,Object> value, String path) {
        Object current = value; for (String part : path.split("\\.")) { if (!(current instanceof Map) || !((Map<String,Object>) current).containsKey(part)) return false; current=((Map<String,Object>) current).get(part); } return true;
    }
    @SuppressWarnings("unchecked") static void putPath(Map<String,Object> value, String path, Object leaf) {
        Map<String,Object> current=value; String[] parts=path.split("\\."); for(int i=0;i<parts.length-1;i++) { Object next=current.get(parts[i]); if(!(next instanceof Map)) { next=new LinkedHashMap<String,Object>(); current.put(parts[i], next); } current=(Map<String,Object>)next; } current.put(parts[parts.length-1], copyValue(leaf));
    }
    @SuppressWarnings("unchecked") static void removePath(Map<String,Object> value, String path) {
        String[] parts=path.split("\\."); List<Map<String,Object>> parents=new ArrayList<Map<String,Object>>(); Map<String,Object> current=value; parents.add(current); for(int i=0;i<parts.length-1;i++) { Object next=current.get(parts[i]); if(!(next instanceof Map)) return; current=(Map<String,Object>)next; parents.add(current); } current.remove(parts[parts.length-1]); for(int i=parents.size()-1;i>0;i--) if(parents.get(i).isEmpty()) parents.get(i-1).remove(parts[i-1]);
    }
    @SuppressWarnings("unchecked") static Map<String,Object> copy(Map<String,Object> value) { Map<String,Object> result=new LinkedHashMap<String,Object>(); for(Map.Entry<String,Object> entry:value.entrySet()) result.put(entry.getKey(), copyValue(entry.getValue())); return result; }
    @SuppressWarnings("unchecked") private static Object copyValue(Object value) { if(value instanceof Map) return copy((Map<String,Object>)value); if(value instanceof List) return new ArrayList<Object>((List<Object>)value); return value; }
    private static final class Leaf { private final String path; private final Object value; private Leaf(String path, Object value) { this.path=path; this.value=value; } }
}
