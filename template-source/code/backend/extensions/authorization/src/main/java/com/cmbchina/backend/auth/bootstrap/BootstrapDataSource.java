package com.cmbchina.backend.auth.bootstrap;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.springframework.util.PropertyPlaceholderHelper;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Standalone Bee ds1 configuration; never starts Spring or falls back to another datasource. */
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
        // 1016 stores local ds1 values in application-dev.yml, including its own active declaration.
        if (profiles == null) profiles = "dev";
        for (String profile : profiles.split(",", -1)) {
            profile = profile.trim();
            if (!profile.matches("[A-Za-z0-9][A-Za-z0-9_-]*"))
                throw new IllegalArgumentException("Invalid Bootstrap profile");
            read(application.resolveSibling("application-" + profile + ".yml"), properties);
        }
        validateComponents(properties);
        String prefix = "bee.persistence.ds1.datasource.";
        String driver = resolve(required(properties, prefix + "driver-class-name"), properties);
        String url = resolve(required(properties, prefix + "url"), properties);
        String username = resolve(required(properties, prefix + "username"), properties);
        String password = resolve(required(properties, prefix + "password"), properties);
        if (!"com.tencentcloud.tdsql.mysql.cj.jdbc.Driver".equals(driver) || !url.startsWith("jdbc:tdsql-mysql://")
                || username.trim().isEmpty()) throw new IllegalArgumentException("Invalid Bee/TDSQL ds1 configuration");
        return new Settings(driver, url, username, password);
    }

    private static void validateComponents(Map<String, String> properties) {
        String url = required(properties, "bee.persistence.ds1.datasource.url");
        if (url.contains("${datasource.ds1.url}")) {
            String host = resolve(required(properties, "datasource.ds1.url"), properties);
            String port = resolve(required(properties, "datasource.ds1.port"), properties);
            String schema = resolve(required(properties, "datasource.ds1.schema"), properties);
            if (!host.matches("[A-Za-z0-9.-]+|\\[[0-9A-Fa-f:]+\\]")
                    || !port.matches("[0-9]{1,5}") || Integer.parseInt(port) < 1 || Integer.parseInt(port) > 65535
                    || !schema.matches("[A-Za-z0-9_-]+"))
                throw new IllegalArgumentException("Invalid TDSQL connection component");
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
            // Environment is consulted only for explicit references, never as an implicit ds1 override.
            String upper = key.toUpperCase(java.util.Locale.ROOT);
            if (value == null && !upper.startsWith("DB_") && !upper.startsWith("MYSQL_")
                    && !upper.startsWith("SPRING_DATASOURCE_")) value = System.getenv(key);
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
