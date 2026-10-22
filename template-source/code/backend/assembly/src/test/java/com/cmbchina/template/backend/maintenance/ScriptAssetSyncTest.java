package com.cmbchina.template.backend.maintenance;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ScriptAssetSyncTest {
    @Test void scriptsHaveUniqueOwnerAndSupportModifyDeleteAndCreateSync() throws Exception {
        Path root = fixture();
        WorkspaceState state = new BackendAssembler(root).assemble("authorization", true);
        assertEquals("authorization", state.owners.get("scripts/bootstrap.sh"));
        assertFalse(WorkspaceStatus.hasChanges(root, state));
        write(root.resolve("workspace/scripts/bootstrap.sh"), "changed\n");
        Files.delete(root.resolve("workspace/scripts/bootstrap.ps1"));
        write(root.resolve("workspace/scripts/new.sh"), "new\n");
        WorkspaceStatus.Report report = WorkspaceStatus.scan(root, state);
        assertTrue(report.modified.contains("scripts/bootstrap.sh"));
        assertTrue(report.deleted.contains("scripts/bootstrap.ps1"));
        assertTrue(report.created.contains("scripts/new.sh"));
        new WorkspaceSync(root).sync("authorization");
        assertEquals("changed\n", new String(Files.readAllBytes(root.resolve("extensions/authorization/scripts/bootstrap.sh")), StandardCharsets.UTF_8));
        assertFalse(Files.exists(root.resolve("extensions/authorization/scripts/bootstrap.ps1")));
        assertTrue(Files.exists(root.resolve("extensions/authorization/scripts/new.sh")));
        state = new BackendAssembler(root).assemble("authorization", true);
        assertEquals("authorization", state.owners.get("scripts/new.sh"));
        assertFalse(WorkspaceStatus.hasChanges(root, state));
    }
    @Test void scriptCollisionIsRejected() throws Exception {
        Path root = fixture();
        write(root.resolve("base/scripts/bootstrap.sh"), "base\n");
        assertThrows(TemplateException.class, () -> new BackendAssembler(root).assemble("authorization", true));
    }
    private Path fixture() throws Exception {
        Path root = Files.createTempDirectory("script-assets");
        write(root.resolve("assembly/profiles.yaml"), "authorization:\n  extensions: [authorization]\n  editTarget: authorization\n");
        write(root.resolve("base/pom.xml"), "<project><modelVersion>4.0.0</modelVersion><dependencies/></project>");
        write(root.resolve("base/src/main/java/com/cmbchina/backend/Application.java"), "package com.cmbchina.backend;\nimport org.springframework.boot.autoconfigure.SpringBootApplication;\n@SpringBootApplication\npublic class Application {}\n");
        write(root.resolve("extensions/authorization/extension.yaml"), "id: authorization\nrequires: []\ncontributes: {}\n");
        write(root.resolve("extensions/authorization/scripts/bootstrap.sh"), "shell\n");
        write(root.resolve("extensions/authorization/scripts/bootstrap.ps1"), "powershell\n");
        return root;
    }
    private void write(Path path, String value) throws Exception {
        Files.createDirectories(path.getParent()); Files.write(path, value.getBytes(StandardCharsets.UTF_8));
    }
}
