package com.cmbchina.backend.auth.bootstrap;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BootstrapDataSourceTest {
    @TempDir Path directory;

    private Path config(String host, String port, String schema) throws Exception {
        Path application = directory.resolve("application.yml");
        String yaml = "spring:\n  datasource:\n"
                + "    driver-class-name: com.mysql.cj.jdbc.Driver\n"
                + "    url: jdbc:mysql://${datasource.ds1.url}:${datasource.ds1.port}/${datasource.ds1.schema}\n"
                + "    username: ${datasource.ds1.username}\n    password: ${datasource.ds1.password}\n"
                + "datasource:\n  ds1:\n    url: base-host\n    port: 3306\n    schema: base_database\n"
                + "    username: fixture\n    password: fixture-only\n";
        Files.write(application, yaml.getBytes(StandardCharsets.UTF_8));
        Files.write(directory.resolve("application-local.yml"), ("datasource:\n  ds1:\n    url: " + host
                + "\n    port: " + port + "\n    schema: " + schema + "\n").getBytes(StandardCharsets.UTF_8));
        return application;
    }

    @Test void profileComponentsBuildMysqlUrlAndResolveCredentials() throws Exception {
        BootstrapDataSource.Settings settings = BootstrapDataSource.load(config("127.0.0.1", "13306", "test_db"), "local");
        assertEquals("jdbc:mysql://127.0.0.1:13306/test_db", settings.url);
        assertEquals("com.mysql.cj.jdbc.Driver", settings.driver);
        assertEquals("fixture", settings.username);
        assertEquals("fixture-only", settings.password);
    }

    @Test void fullJdbcUrlCannotBeUsedAsHost() throws Exception {
        assertThrows(IllegalArgumentException.class, () ->
                BootstrapDataSource.load(config("jdbc:mysql://localhost:3306/db", "3306", "test_db"), "local"));
    }

    @Test void invalidPortAndSchemaFailBeforeConnecting() throws Exception {
        assertThrows(IllegalArgumentException.class, () ->
                BootstrapDataSource.load(config("127.0.0.1", "65536", "test_db"), "local"));
        assertThrows(IllegalArgumentException.class, () ->
                BootstrapDataSource.load(config("127.0.0.1", "3306", "db?user=injected"), "local"));
    }
}
