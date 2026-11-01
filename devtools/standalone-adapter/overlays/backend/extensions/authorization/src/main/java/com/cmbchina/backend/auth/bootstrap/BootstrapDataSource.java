package com.cmbchina.backend.auth.bootstrap;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.springframework.util.PropertyPlaceholderHelper;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Standalone standard MySQL configuration; never starts Spring or falls back to another datasource. */
final class BootstrapDataSource {
    private static final PropertyPlaceholderHelper PLACEHOLDER = new PropertyPlaceholderHelper("${", "}", ":", false);
    private BootstrapDataSource() { }

    static Settings load(Path application, String requestedProfile) throws Exception {
        Map<String, String> properties = new LinkedHashMap<String, String>();
        read(application, properties);
        String profiles = requestedProfile;
        if (profiles == null) profiles = System.getProperty("spring.profiles.active");
        if (profiles == null) profiles = System.getenv("SPRING_PROFILES_ACTIVE");
        if (profiles == null) profiles = properties.get("spring.profiles.active");
        // Preserve the original Bootstrap dev fallback; explicit local is also supported.
        if (profiles == null) profiles = "dev";
        for (String profile : profiles.split(",", -1)) {
            profile = profile.trim();
            if (!profile.matches("[A-Za-z0-9][A-Za-z0-9_-]*"))
                throw new IllegalArgumentException("Invalid Bootstrap profile");
            read(application.resolveSibling("application-" + profile + ".yml"), properties);
        }
        validateComponents(properties);
        String prefix = "spring.datasource.";
        String driver = resolve(required(properties, prefix + "driver-class-name"), properties);
        String url = resolve(required(properties, prefix + "url"), properties);
        String username = resolve(required(properties, prefix + "username"), properties);
        String password = resolve(required(properties, prefix + "password"), properties);
        if (!"com.mysql.cj.jdbc.Driver".equals(driver) || !url.startsWith("jdbc:mysql://")
                || username.trim().isEmpty()) throw new IllegalArgumentException("Invalid MySQL datasource configuration");
        return new Settings(driver, url, username, password);
    }

    private static void validateComponents(Map<String, String> properties) {
        String url = required(properties, "spring.datasource.url");
        if (url.contains("${datasource.ds1.url}")) {
            String host = resolve(required(properties, "datasource.ds1.url"), properties);
            String port = resolve(required(properties, "datasource.ds1.port"), properties);
            String schema = resolve(required(properties, "datasource.ds1.schema"), properties);
            if (!host.matches("[A-Za-z0-9.-]+|\\[[0-9A-Fa-f:]+\\]")
                    || !port.matches("[0-9]{1,5}") || Integer.parseInt(port) < 1 || Integer.parseInt(port) > 65535
                    || !schema.matches("[A-Za-z0-9_-]+"))
                throw new IllegalArgumentException("Invalid MySQL connection component");
        }
    }

    private static void read(Path path, Map<String, String> properties) throws Exception {
        try (InputStream stream = Files.newInputStream(path)) {
            Object root = new Yaml(new SafeConstructor()).load(stream);
            if (!(root instanceof Map)) throw new IllegalArgumentException("Invalid Bootstrap YAML");
            flatten("", (Map<?, ?>) root, properties);
        }
    }

    private static void flatten(String prefix, Map<?, ?> value, Map<String, String> properties) {
        for (Map.Entry<?, ?> entry : value.entrySet()) {
            String key = prefix + entry.getKey();
            if (entry.getValue() instanceof Map) flatten(key + ".", (Map<?, ?>) entry.getValue(), properties);
            else if (entry.getValue() != null) properties.put(key, String.valueOf(entry.getValue()));
        }
    }

    private static String required(Map<String, String> properties, String key) {
        String value = properties.get(key);
        if (value == null) throw new IllegalArgumentException("Missing Bootstrap property: " + key);
        return value;
    }

    private static String resolve(String input, Map<String, String> properties) {
        return PLACEHOLDER.replacePlaceholders(input, key -> {
            String value = properties.get(key);
            // Only explicit YAML references consult the environment; DATASOURCE_DS1_* supplies the local overrides.
            if (value == null) value = System.getenv(key);
            return value;
        });
    }

    static final class Settings {
        final String driver, url, username, password;
        Settings(String driver, String url, String username, String password) {
            this.driver = driver; this.url = url; this.username = username; this.password = password;
        }
    }
}
