package com.cmbchina.backend.auth.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.File;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BootstrapContractTest {
    @TempDir Path temporary;
    private final ObjectMapper json = new ObjectMapper();
    private Path repository() {
        Path path = Paths.get("").toAbsolutePath();
        while (!Files.isDirectory(path.resolve("template-source"))) path = path.getParent();
        return path;
    }
    private Path workspace() throws Exception {
        Path workspace = temporary.resolve("workspace with ' and \" quotes");
        Path backend = workspace.resolve("backend");
        Path source = repository().resolve("template-source/code/backend");
        write(workspace.resolve(".devagentstudio/application.json"), "{\"authorization\":{\"enabled\":true,\"initialAdministratorSubjects\":[\"bootstrap-test-admin\"]}}");
        Path plan = workspace.resolve(".devagentstudio/plans/technical-plan.json");
        Files.createDirectories(plan.getParent());
        Files.copy(Paths.get("src/test/resources/technical-plan.json"), plan);
        for (String name : Arrays.asList("application.yml", "application-dev.yml")) {
            Path dest = backend.resolve("src/main/resources/" + name);
            Files.createDirectories(dest.getParent());
            Files.copy(source.resolve("base/src/main/resources/" + name), dest);
        }
        Path ddl = backend.resolve("sql/authorization/schema.sql"); Files.createDirectories(ddl.getParent());
        Files.copy(source.resolve("extensions/authorization/sql/authorization/schema.sql"), ddl);
        return backend;
    }
    private Result run(Path backend, String... args) throws Exception { return run(backend, false, args); }
    private Result run(Path backend, boolean scriptTransport, String... args) throws Exception {
        List<String> command = new ArrayList<String>();
        command.add(Paths.get(System.getProperty("java.home"), "bin/java").toString());
        command.add("-cp");
        String[] entries = System.getProperty("java.class.path").split(File.pathSeparator);
        List<String> absolute = new ArrayList<String>();
        for (String entry : entries) absolute.add(Paths.get(entry).toAbsolutePath().toString());
        command.add(String.join(File.pathSeparator, absolute));
        command.add(AuthorizationBootstrapCommand.class.getName());
        if (!scriptTransport) command.addAll(Arrays.asList(args));
        Path log = temporary.resolve(UUID.randomUUID() + ".log");
        ProcessBuilder builder = new ProcessBuilder(command).directory(backend.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().keySet().removeIf(key -> key.startsWith("AUTHORIZATION_BOOTSTRAP_ARGUMENT_") || key.equals("SPRING_PROFILES_ACTIVE"));
        builder.environment().put("DB_URL", "DO_NOT_CONNECT_TO_HOST_DATABASE");
        if (scriptTransport) {
            builder.environment().put("AUTHORIZATION_BOOTSTRAP_ARGUMENT_COUNT", String.valueOf(args.length));
            for (int i = 0; i < args.length; i++) builder.environment().put("AUTHORIZATION_BOOTSTRAP_ARGUMENT_" + i, args[i]);
        }
        Process process = builder.start();
        assertTrue(process.waitFor(45, java.util.concurrent.TimeUnit.SECONDS));
        return new Result(process.exitValue(), new String(Files.readAllBytes(log), StandardCharsets.UTF_8));
    }
    @Test void v3DefaultWorkspaceAndBeeProfileDryRun() throws Exception {
        Result result = run(workspace(), "--dry-run");
        assertEquals(0, result.code, result.output);
        assertTrue(result.output.contains("resources=2"));
        assertFalse(result.output.contains("password"));
    }
    @Test void defaultDdlRejectsMissingOrInvalidNewAssetWithoutFallingBackToLegacyPaths() throws Exception {
        Path backend = workspace();
        Path ddl = backend.resolve("sql/authorization/schema.sql");
        byte[] original = Files.readAllBytes(ddl);
        for (String legacy : Arrays.asList("docs/auth/sql/ddl.sql", "migrations/001-schema.sql")) {
            Path path = backend.resolve(legacy); Files.createDirectories(path.getParent()); Files.write(path, original);
        }
        Files.delete(ddl);
        assertNotEquals(0, run(backend, "--dry-run").code);
        write(ddl, "invalid DDL");
        assertNotEquals(0, run(backend, "--dry-run").code);
        Files.write(ddl, original);
        assertEquals(0, run(backend, "--dry-run").code);
    }
    @Test void scriptTransportPreservesQuotedWorkspacePathsAndFailuresDoNotExposeSecrets() throws Exception {
        Path backend = workspace();
        Result result = run(backend, true, "--dry-run", "--technical-plan=" + backend.getParent().resolve(".devagentstudio/plans/technical-plan.json"));
        assertEquals(0, result.code, result.output);
        Path application = backend.getParent().resolve(".devagentstudio/application.json");
        write(application, "{\"password\":\"bootstrap-secret-do-not-log\" broken JSON}");
        Result failure = run(backend, "--dry-run");
        assertNotEquals(0, failure.code);
        assertFalse(failure.output.contains("bootstrap-secret-do-not-log"));
    }
    @Test void rejectsOldVersionDuplicateResourceExtraSystemAndWrongSystemRole() throws Exception {
        Path backend = workspace(); Path plan = backend.getParent().resolve(".devagentstudio/plans/technical-plan.json");
        ObjectNode original = (ObjectNode) json.readTree(plan.toFile());
        for (int failure = 0; failure < 5; failure++) {
            ObjectNode changed = original.deepCopy(); ObjectNode manifest = (ObjectNode) changed.path("authorization_manifest");
            if (failure == 0) manifest.put("schema_version", "authorization-manifest.v2");
            if (failure == 1) manifest.withArray("resources").add(manifest.path("resources").get(0).deepCopy());
            if (failure == 2) for (com.fasterxml.jackson.databind.JsonNode resource : manifest.path("resources"))
                if ("business".equals(resource.path("origin").asText())) ((ObjectNode) resource).put("origin", "system");
            if (failure == 3) ((ObjectNode) manifest.path("systemAuthorization")).put("adminRoleSeedKey", "SYSTEM_ADMIN");
            if (failure == 4) changed.put("confirmation_status", "draft");
            json.writeValue(plan.toFile(), changed);
            assertEquals(2, run(backend, "--dry-run").code);
        }
    }
    @Test void beePlaceholderResolutionRejectsMissingCyclicOrWrongDriver() throws Exception {
        Path backend = workspace(); Path application = backend.resolve("src/main/resources/application.yml");
        BootstrapDataSource.Settings settings = BootstrapDataSource.load(application, null);
        assertEquals("com.tencentcloud.tdsql.mysql.cj.jdbc.Driver", settings.driver);
        assertTrue(settings.url.startsWith("jdbc:tdsql-mysql://127.0.0.1:3306/database?"));
        assertEquals("root", settings.username);
        String baseline = new String(Files.readAllBytes(application), StandardCharsets.UTF_8);
        for (String invalid : Arrays.asList(baseline.replace("${datasource.ds1.port}", "${missing}"),
                baseline.replace("${datasource.ds1.port}", "${bee.persistence.ds1.datasource.url}"),
                baseline.replace("com.tencentcloud.tdsql.mysql.cj.jdbc.Driver", "com.mysql.cj.jdbc.Driver"))) {
            write(application, invalid);
            Result result = run(backend, "--dry-run");
            assertNotEquals(0, result.code); assertFalse(result.output.contains("password"));
        }
        write(application, baseline);
        assertNotEquals(0, run(backend, "--dry-run", "--profile=absent").code);
        assertNotEquals(0, run(backend, "--dry-run", "--profile=../dev").code);
    }
    @Test @EnabledIfEnvironmentVariable(named="AUTHORIZATION_BOOTSTRAP_TEST_CONFIG", matches=".+")
    void realTdsqlCreatesOnlyMinimalSystemGrantsAndIsIdempotent() throws Exception {
        Path config = Paths.get(System.getenv("AUTHORIZATION_BOOTSTRAP_TEST_CONFIG")).toAbsolutePath();
        BootstrapDataSource.Settings settings = BootstrapDataSource.load(config, "test");
        Class.forName(settings.driver);
        Path backend = workspace();
        try (Connection connection = DriverManager.getConnection(settings.url, settings.username, settings.password);
             Statement statement = connection.createStatement()) {
            // Dedicated empty schema only; never execute against an application's existing tables.
            assertTrue(connection.getCatalog().endsWith("_bootstrap_test"));
            try (ResultSet tables = statement.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()")) {
                assertTrue(tables.next()); assertEquals(0, tables.getInt(1));
            }
            try {
                for (int i = 0; i < 2; i++) assertEquals(0, run(backend, "--application-yml=" + config, "--profile=test").code);
                assertCount(statement, "SELECT COUNT(*) FROM `resource`", 2);
                assertCount(statement, "SELECT COUNT(*) FROM `role` WHERE role_id='SYSTEM_ADMIN' AND is_system=1", 1);
                assertCount(statement, "SELECT COUNT(*) FROM role_member WHERE member_id='bootstrap-test-admin'", 1);
                assertCount(statement, "SELECT COUNT(*) FROM role_resource WHERE role_id='SYSTEM_ADMIN' AND resource_key='system_authorization_management'", 1);
                assertCount(statement, "SELECT COUNT(*) FROM role_resource WHERE resource_key='orders'", 0);
                assertCount(statement, "SELECT COUNT(*) FROM `role` WHERE role_id='reader'", 0);
            } finally {
                for (String table : Arrays.asList("role_member", "role_resource", "resource", "role")) statement.execute("DROP TABLE IF EXISTS `" + table + "`");
            }
        }
    }
    private void assertCount(Statement statement, String sql, int expected) throws Exception {
        try (ResultSet rows = statement.executeQuery(sql)) { assertTrue(rows.next()); assertEquals(expected, rows.getInt(1)); }
    }
    private void write(Path path, String value) throws Exception { Files.createDirectories(path.getParent()); Files.write(path, value.getBytes(StandardCharsets.UTF_8)); }
    private static class Result { final int code; final String output; Result(int code, String output) { this.code=code; this.output=output; } }
}
