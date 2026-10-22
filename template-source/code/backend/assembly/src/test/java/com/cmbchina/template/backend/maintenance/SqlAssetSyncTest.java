package com.cmbchina.template.backend.maintenance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class SqlAssetSyncTest {
    @TempDir Path root;
    private static final String SQL = "sql/authorization/schema.sql";

    @Test void realProfilesDeliverOnlyAuthorizationSqlAndSyncKeepsOtherOwnersUntouched() throws Exception {
        Path source = Paths.get("").toAbsolutePath();
        while (!Files.isDirectory(source.resolve("template-source"))) source = source.getParent();
        source = source.resolve("template-source/code/backend");
        copyTree(source.resolve("base"), root.resolve("base"));
        copyTree(source.resolve("extensions"), root.resolve("extensions"));
        Files.createDirectories(root.resolve("assembly"));
        Files.copy(source.resolve("assembly/profiles.yaml"), root.resolve("assembly/profiles.yaml"));
        byte[] original = Files.readAllBytes(root.resolve("extensions/authorization/" + SQL));
        for (String profile : Arrays.asList("base", "login", "authorization", "full")) {
            WorkspaceState state = new BackendAssembler(root).assemble(profile, true);
            boolean authorization = "authorization".equals(profile) || "full".equals(profile);
            assertEquals(authorization, Files.exists(root.resolve("workspace/" + SQL)), profile);
            for (String script : Arrays.asList("bootstrap-authorization.sh", "bootstrap-authorization.ps1"))
                assertEquals(authorization, Files.exists(root.resolve("workspace/scripts/" + script)), profile);
            assertFalse(Files.exists(root.resolve("workspace/docs/auth/sql/ddl.sql")));
            assertFalse(Files.exists(root.resolve("workspace/migrations/001-schema.sql")));
            if (authorization) {
                assertArrayEquals(original, Files.readAllBytes(root.resolve("workspace/" + SQL)));
                assertEquals("authorization", state.owners.get(SQL));
            }
        }
        WorkspaceState state = new BackendAssembler(root).assemble("authorization", true);
        byte[] base = Files.readAllBytes(root.resolve("base/pom.xml"));
        byte[] login = Files.readAllBytes(root.resolve("extensions/login/extension.yaml"));
        byte[] changed = (new String(original, StandardCharsets.UTF_8) + "\n-- sync test\n").getBytes(StandardCharsets.UTF_8);
        Files.write(root.resolve("workspace/" + SQL), changed);
        assertTrue(WorkspaceStatus.scan(root, state).modified.contains(SQL));
        assertEquals(root.resolve("extensions/authorization/" + SQL), WorkspaceStatus.source(root, state.owners.get(SQL), SQL));
        new WorkspaceSync(root).sync("authorization");
        assertArrayEquals(changed, Files.readAllBytes(root.resolve("extensions/authorization/" + SQL)));
        assertArrayEquals(base, Files.readAllBytes(root.resolve("base/pom.xml")));
        assertArrayEquals(login, Files.readAllBytes(root.resolve("extensions/login/extension.yaml")));
        state = new BackendAssembler(root).assemble("authorization", true);
        assertFalse(WorkspaceStatus.hasChanges(root, state));
    }

    private void copyTree(Path from, Path to) throws Exception {
        Files.walkFileTree(from, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(to.resolve(from.relativize(directory))); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, to.resolve(from.relativize(file))); return FileVisitResult.CONTINUE;
            }
        });
    }
}
