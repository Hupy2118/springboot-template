package com.xcodeagent.template.engine.source;

import com.xcodeagent.template.engine.core.v2.TemplateRelease;
import com.xcodeagent.template.engine.core.v2.TemplateStateV2;
import com.xcodeagent.template.engine.core.v2.V2ProjectGenerator;
import com.xcodeagent.template.engine.core.v2.CapabilityState;
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

/** Locks the pristine V2 extension surfaces used by all atomic strategies. */
class BaseSurfaceContractTest {
    @Test
    void pristineBaseExposesExactlyOneStableAnchorPerExtensionSurface() throws Exception {
        Path base = repositoryRoot().resolve("template-source/base");
        assertAnchor(base, "frontend/src/generated/capabilityProviders.tsx", "// xcodeagent:capability-providers");
        assertAnchor(base, "frontend/src/generated/capabilityRoutes.tsx", "// xcodeagent:capability-root-routes");
        assertAnchor(base, "frontend/src/generated/capabilityRoutes.tsx", "// xcodeagent:capability-page-routes");
        assertAnchor(base, "frontend/src/generated/capabilityRoutes.tsx", "// xcodeagent:capability-page-wrappers");
        assertAnchor(base, "frontend/src/generated/capabilityMenus.ts", "// xcodeagent:capability-menu-transforms");
        assertAnchor(base, "backend/src/main/java/com/cmbchina/backend/common/config/CapabilityWebMvcConfiguration.java",
                "// xcodeagent:capability-interceptors");
    }

    @Test
    void baseKeepsTheRequiredStaticAndDeterministicSurfaceStructure() throws Exception {
        Path base = repositoryRoot().resolve("template-source/base");
        String routes = read(base, "frontend/src/generated/capabilityRoutes.tsx");
        String menus = read(base, "frontend/src/generated/capabilityMenus.ts");
        String webMvc = read(base, "backend/src/main/java/com/cmbchina/backend/common/config/CapabilityWebMvcConfiguration.java");
        assertTrue(routes.indexOf("capability-root-routes") < routes.indexOf("capability-page-routes"));
        assertTrue(routes.indexOf("capability-page-routes") < routes.indexOf("capability-page-wrappers"));
        assertTrue(routes.contains("route.handle?.capabilityEntry"));
        assertFalse(menus.contains(".reduce("));
        assertFalse(menus.contains(".map("));
        assertTrue(menus.contains("let current = menus;"));
        assertTrue(webMvc.contains("private final ApplicationContext applicationContext;"));
        assertTrue(webMvc.contains("public CapabilityWebMvcConfiguration(ApplicationContext applicationContext)"));
    }

    @Test
    void emptyCapabilityV2GenerationIsTheUnmodifiedBase() throws Exception {
        Path root = repositoryRoot().resolve("template-source");
        TemplateRelease release = new CapabilityV2Loader().load(root);
        TemplateStateV2 empty = new TemplateStateV2(release.revision(), Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());
        Map<String, String> generated = new V2ProjectGenerator(root, release).generate(empty);
        assertEquals(read(root.resolve("base"), "frontend/src/generated/capabilityProviders.tsx"), generated.get("frontend/src/generated/capabilityProviders.tsx"));
        assertEquals(read(root.resolve("base"), "frontend/src/generated/capabilityRoutes.tsx"), generated.get("frontend/src/generated/capabilityRoutes.tsx"));
        assertEquals(read(root.resolve("base"), "frontend/src/generated/capabilityMenus.ts"), generated.get("frontend/src/generated/capabilityMenus.ts"));
        assertEquals(read(root.resolve("base"), "backend/src/main/java/com/cmbchina/backend/common/config/CapabilityWebMvcConfiguration.java"), generated.get("backend/src/main/java/com/cmbchina/backend/common/config/CapabilityWebMvcConfiguration.java"));
    }

    @Test
    void capabilityDocumentationIsGeneratedOnlyWithItsCapability() throws Exception {
        Path root = repositoryRoot().resolve("template-source");
        TemplateRelease release = new CapabilityV2Loader().load(root);
        Map<String, CapabilityState> login = new LinkedHashMap<String, CapabilityState>();
        login.put("login", new CapabilityState(true, Collections.emptyMap()));

        Map<String, String> loginGenerated = new V2ProjectGenerator(root, release).generate(
                new TemplateStateV2(release.revision(), login, login, Collections.emptyMap()));
        assertTrue(loginGenerated.containsKey("backend/docs/capabilities/login.md"));
        assertTrue(loginGenerated.containsKey("frontend/docs/capabilities/login.md"));
        assertFalse(loginGenerated.containsKey("backend/docs/capabilities/authorization.md"));
        assertFalse(loginGenerated.containsKey("frontend/docs/capabilities/authorization.md"));

        Map<String, CapabilityState> both = new LinkedHashMap<String, CapabilityState>(login);
        both.put("authorization", new CapabilityState(true, Collections.emptyMap()));
        Map<String, String> bothGenerated = new V2ProjectGenerator(root, release).generate(
                new TemplateStateV2(release.revision(), both, both, Collections.emptyMap()));
        assertTrue(bothGenerated.containsKey("backend/docs/capabilities/authorization.md"));
        assertTrue(bothGenerated.containsKey("frontend/docs/capabilities/authorization.md"));
    }

    @Test
    void atomicStrategiesKeepProviderAndInterceptorOrderOnTheFixedSurfaces() throws Exception {
        Path root = repositoryRoot().resolve("template-source");
        TemplateRelease release = new CapabilityV2Loader().load(root);
        Map<String, CapabilityState> effective = new LinkedHashMap<String, CapabilityState>();
        effective.put("login", new CapabilityState(true, Collections.emptyMap()));
        effective.put("authorization", new CapabilityState(true, Collections.emptyMap()));
        Map<String, String> generated = new V2ProjectGenerator(root, release).generate(
                new TemplateStateV2(release.revision(), effective, effective, Collections.emptyMap()));
        String providers = generated.get("frontend/src/generated/capabilityProviders.tsx");
        String webMvc = generated.get("backend/src/main/java/com/cmbchina/backend/common/config/CapabilityWebMvcConfiguration.java");
        assertTrue(providers.indexOf("xcodeagent:login-provider:begin") < providers.indexOf("xcodeagent:authorization-provider:begin"));
        assertTrue(webMvc.indexOf("xcodeagent:login-interceptor:begin") < webMvc.indexOf("xcodeagent:authorization-interceptor:begin"));
        assertTrue(webMvc.contains("applicationContext.getBean("));
        assertFalse(webMvc.contains("@org.springframework.beans.factory.annotation.Autowired"));
    }

    private static void assertAnchor(Path base, String relative, String anchor) throws Exception {
        assertEquals(1, count(read(base, relative), anchor), relative + " must contain one stable anchor");
    }
    private static String read(Path base, String relative) throws Exception { return new String(Files.readAllBytes(base.resolve(relative)), StandardCharsets.UTF_8); }
    private static int count(String value, String needle) { int total = 0; for (int at = value.indexOf(needle); at >= 0; at = value.indexOf(needle, at + needle.length())) total++; return total; }
    private static Path repositoryRoot() { Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath(); while (current != null && !Files.isDirectory(current.resolve("template-source"))) current = current.getParent(); if (current == null) throw new IllegalStateException("repository root not found"); return current; }
}
