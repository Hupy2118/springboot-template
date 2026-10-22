package com.cmbchina.template.backend.maintenance;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceYamlContributionSyncTest {
    @Test
    void authorizationSyncOwnsNewYamlLeavesWithoutPollutingBase() throws Exception {
        Path root = fixture();
        new BackendAssembler(root).assemble("authorization", true);
        Path application = root.resolve("workspace/src/main/resources/application.yml");
        java.util.Map<String,Object> applicationValue=YamlContributionCompiler.read(application);
        YamlContributionCompiler.putPath(applicationValue, "bee.authorization.enabled", Boolean.TRUE);
        YamlContributionCompiler.write(application, applicationValue);
        Path dev = root.resolve("workspace/src/main/resources/application-dev.yml");
        java.util.Map<String,Object> devValue=YamlContributionCompiler.read(dev);
        YamlContributionCompiler.putPath(devValue, "yht.authorization.endpoint", "http://localhost/authz");
        YamlContributionCompiler.write(dev, devValue);

        new WorkspaceSync(root).sync("authorization");

        String baseApplication = read(root.resolve("base/src/main/resources/application.yml"));
        String baseDev = read(root.resolve("base/src/main/resources/application-dev.yml"));
        String authorization = read(root.resolve("extensions/authorization/extension.yaml"));
        assertFalse(baseApplication.contains("authorization:"));
        assertFalse(baseDev.contains("authorization:"));
        assertTrue(authorization.contains("applicationProfileYaml:"));
        assertTrue(authorization.contains("endpoint: http://localhost/authz"));
        assertTrue(authorization.contains("enabled: true"));

        new BackendAssembler(root).assemble("authorization", true);
        assertTrue(read(application).contains("enabled: true"));
        assertTrue(read(dev).contains("endpoint: http://localhost/authz"));
    }

    private static Path fixture() throws Exception {
        Path root = Files.createTempDirectory("backend-yaml-sync");
        write(root.resolve("assembly/profiles.yaml"), "authorization:\n  extensions: [authorization]\n  editTarget: authorization\n");
        write(root.resolve("base/pom.xml"), "<project><modelVersion>4.0.0</modelVersion><dependencies/></project>");
        write(root.resolve("base/src/main/java/com/cmbchina/backend/Application.java"), "package com.cmbchina.backend;\nimport org.springframework.boot.autoconfigure.SpringBootApplication;\n@SpringBootApplication\npublic class Application {}\n");
        write(root.resolve("base/src/main/resources/application.yml"), "server:\n  port: 8080\n");
        write(root.resolve("base/src/main/resources/application-dev.yml"), "spring:\n  profiles:\n    active: dev\n");
        write(root.resolve("extensions/login/extension.yaml"), "id: login\nrequires: []\ncontributes:\n  applicationYaml:\n    bee:\n      auth:\n        enabled: true\n  applicationProfileYaml:\n    dev:\n      yht:\n        authentication:\n          client-id: login\n");
        write(root.resolve("extensions/authorization/extension.yaml"), "id: authorization\nrequires: [login]\ncontributes: {}\n");
        return root;
    }
    private static void write(Path file, String value) throws Exception { Files.createDirectories(file.getParent()); Files.write(file, value.getBytes(StandardCharsets.UTF_8)); }
    private static String read(Path file) throws Exception { return new String(Files.readAllBytes(file), StandardCharsets.UTF_8); }
}
