package com.devagentstudio.template.engine.core.v3;

import com.devagentstudio.template.engine.core.v3.ExtensionResolver.ResolvedExtensions;
import com.devagentstudio.template.engine.core.v3.V3ProjectMaterializer;
import com.devagentstudio.template.engine.source.v3.CodeTemplateReleaseLoader;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeTemplateReleaseLoaderTest {
    @Test
    void loadsV3ReleaseAndResolvesAuthorizationClosureDeterministically() throws Exception {
        Path root = repositoryRoot();
        CodeTemplateRelease release = new CodeTemplateReleaseLoader(root.resolve("template-source/code")).load();
        assertEquals("2026.09.23.4", release.revision());
        assertTrue(release.extensions().containsKey("login"));
        assertTrue(release.extensions().containsKey("authorization"));

        Map<String, Object> auth = new LinkedHashMap<String, Object>();
        auth.put("enabled", Boolean.TRUE); auth.put("config", Collections.emptyMap());
        Map<String, Object> requested = new LinkedHashMap<String, Object>(); requested.put("authorization", auth);
        ResolvedExtensions resolved = new ExtensionResolver(release).resolve(requested);
        assertEquals(java.util.Arrays.asList("login", "authorization"), resolved.order());
        assertTrue(resolved.effective().containsKey("login"));
        assertTrue(resolved.effective().containsKey("authorization"));

        Map<String, byte[]> project = new V3ProjectMaterializer(release).materialize(resolved);
        assertTrue(project.containsKey("frontend/src/pages/Login/index.tsx"));
        assertTrue(project.containsKey("backend/migrations/001-schema.sql"));
        String application = utf8(project.get("backend/src/main/java/com/cmbchina/backend/Application.java"));
        String pom = utf8(project.get("backend/pom.xml"));
        String pageRoutes = utf8(project.get("frontend/src/extensions/systemPageRoutes.ts"));
        assertEquals(1, occurrences(application, "@EnableAuthClient"));
        assertEquals(1, occurrences(application, "import com.cmb.bee.auth.client.config.EnableAuthClient;"));
        assertTrue(pom.contains("<artifactId>bee-starter-auth</artifactId>"));
        assertTrue(pageRoutes.contains("authorization_management"));
        assertTrue(project.containsKey("frontend/src/extensions/providers.ts"));
        assertEquals(java.util.Arrays.asList("login"), owners(resolved.managedContributions().get("maven:ZA21:bee-starter-auth")));

        ResolvedExtensions base = new ExtensionResolver(release).resolve(Collections.<String, Object>emptyMap());
        Map<String, byte[]> baseProject = new V3ProjectMaterializer(release).materialize(base);
        assertFalse(baseProject.containsKey("frontend/src/pages/Login/index.tsx"));
        assertFalse(utf8(baseProject.get("backend/pom.xml")).contains("bee-starter-auth"));
    }

    @SuppressWarnings("unchecked")
    private java.util.List<String> owners(Map<String, Object> value) { return (java.util.List<String>) value.get("owners"); }
    private int occurrences(String source, String token) { return (source.length() - source.replace(token, "").length()) / token.length(); }
    private String utf8(byte[] value) { return new String(value, StandardCharsets.UTF_8); }
    private Path repositoryRoot() {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null && !Files.isDirectory(current.resolve("template-source"))) current = current.getParent();
        if (current == null) throw new AssertionError("repository root not found");
        return current;
    }
}
