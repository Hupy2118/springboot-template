package com.cmbchina.template.backend.maintenance;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceSyncContributionTest {
    @Test
    void syncStripsLoginContributionsBeforeWritingBase() throws Exception {
        Path root = fixture();
        new BackendAssembler(root).assemble("login", true);
        Path application = root.resolve("workspace/src/main/java/com/cmbchina/backend/Application.java");
        Files.write(application, read(application).replace("public class Application", "// base edit\npublic class Application").getBytes(StandardCharsets.UTF_8));
        new WorkspaceSync(root).sync("login");
        String baseApplication = read(root.resolve("base/src/main/java/com/cmbchina/backend/Application.java"));
        assertTrue(baseApplication.contains("// base edit"));
        assertFalse(baseApplication.contains("EnableLogin"));
        assertFalse(read(root.resolve("base/pom.xml")).contains("login-auth"));
    }

    @Test
    void syncRejectsAChangedGeneratedContribution() throws Exception {
        Path root = fixture();
        new BackendAssembler(root).assemble("login", true);
        Path pom = root.resolve("workspace/pom.xml");
        Files.write(pom, read(pom).replace("login-auth", "changed-auth").getBytes(StandardCharsets.UTF_8));
        assertThrows(TemplateException.class, () -> new WorkspaceSync(root).sync("login"));
    }

    private static Path fixture() throws Exception {
        Path root = Files.createTempDirectory("backend-template");
        write(root.resolve("assembly/profiles.yaml"), "login:\n  extensions: [login]\n  editTarget: login\n");
        write(root.resolve("base/pom.xml"), "<project><modelVersion>4.0.0</modelVersion><dependencies/></project>");
        write(root.resolve("base/src/main/java/com/cmbchina/backend/Application.java"), "package com.cmbchina.backend;\nimport org.springframework.boot.autoconfigure.SpringBootApplication;\n@SpringBootApplication\npublic class Application {}\n");
        write(root.resolve("extensions/login/extension.yaml"), "id: login\nrequires: []\ncontributes:\n  applicationAnnotations:\n    - annotationClass: com.example.EnableLogin\n  mavenDependencies:\n    - groupId: example\n      artifactId: login-auth\n");
        return root;
    }
    private static void write(Path file, String value) throws Exception { Files.createDirectories(file.getParent()); Files.write(file, value.getBytes(StandardCharsets.UTF_8)); }
    private static String read(Path file) throws Exception { return new String(Files.readAllBytes(file), StandardCharsets.UTF_8); }
}
