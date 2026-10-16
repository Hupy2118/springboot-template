package com.cmbchina.template.backend.maintenance;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Applies the same deterministic, conflict-safe application.yml contributions as V3 Core. */
final class ApplicationYamlCompiler {
    private ApplicationYamlCompiler() { }

    @SuppressWarnings("unchecked")
    static void apply(Path file, List<Map<String,Object>> contributions) {
        if (contributions.isEmpty()) return;
        try {
            Object loaded = new Yaml().load(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            if (!(loaded instanceof Map)) throw new TemplateException("APPLICATION_YAML_INVALID", "application.yml root must be an object");
            Map<String,Object> target = copy((Map<String,Object>) loaded);
            for (Map<String,Object> contribution : contributions) merge(target, contribution, "");
            DumperOptions options = new DumperOptions(); options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK); options.setPrettyFlow(false);
            Files.write(file, new Yaml(options).dump(target).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) { throw new TemplateException("APPLICATION_YAML_INVALID", e.getMessage()); }
    }
    @SuppressWarnings("unchecked") private static void merge(Map<String,Object> target, Map<String,Object> source, String prefix) {
        for (Map.Entry<String,Object> entry : source.entrySet()) {
            String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey(); Object incoming = entry.getValue(); Object existing = target.get(entry.getKey());
            if (incoming instanceof Map) {
                if (existing == null) target.put(entry.getKey(), copy((Map<String,Object>) incoming));
                else if (existing instanceof Map) merge((Map<String,Object>) existing, (Map<String,Object>) incoming, path);
                else throw new TemplateException("APPLICATION_YAML_CONFLICT", path);
            } else if (existing == null || existing.equals(incoming)) target.put(entry.getKey(), incoming);
            else throw new TemplateException("APPLICATION_YAML_CONFLICT", path);
        }
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> copy(Map<String,Object> value) {
        Map<String,Object> result = new LinkedHashMap<String,Object>();
        for (Map.Entry<String,Object> entry : value.entrySet()) { Object item = entry.getValue(); if (item instanceof Map) item = copy((Map<String,Object>) item); else if (item instanceof List) item = new ArrayList<Object>((List<Object>) item); result.put(entry.getKey(), item); }
        return result;
    }
}
