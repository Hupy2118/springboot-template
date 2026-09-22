package com.cmbchina.template.backend.maintenance;

import org.yaml.snakeyaml.Yaml;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class ProfileResolver {
    public static final class Profile {
        public final String name; public final List<String> requested; public final String editTarget;
        Profile(String name, List<String> requested, String editTarget) { this.name = name; this.requested = requested; this.editTarget = editTarget; }
    }
    private final Map<String, Profile> profiles = new LinkedHashMap<String, Profile>();
    @SuppressWarnings("unchecked")
    public ProfileResolver(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            Map<String, Object> raw = new Yaml().load(in);
            for (Map.Entry<String, Object> entry : raw.entrySet()) {
                Map<String, Object> value = (Map<String, Object>) entry.getValue();
                List<String> extensions = new ArrayList<String>();
                Object selected = value.get("extensions");
                if (selected instanceof List) for (Object id : (List<Object>) selected) extensions.add(String.valueOf(id));
                Object target = value.get("editTarget");
                profiles.put(entry.getKey(), new Profile(entry.getKey(), extensions, target == null ? null : String.valueOf(target)));
            }
        } catch (Exception e) { throw new TemplateException("INVALID_PROFILE", e.getMessage()); }
    }
    public Profile get(String name) {
        Profile profile = profiles.get(name);
        if (profile == null) throw new TemplateException("UNKNOWN_PROFILE", name);
        return profile;
    }
}
