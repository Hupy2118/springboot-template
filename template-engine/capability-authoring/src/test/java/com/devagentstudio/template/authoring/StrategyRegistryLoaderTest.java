package com.devagentstudio.template.authoring;

import com.devagentstudio.template.engine.source.StrategyRegistry;
import com.devagentstudio.template.engine.source.StrategyRegistryLoader;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrategyRegistryLoaderTest {
    @Test
    void exposesThePublishedRegistryAsReadOnlyAuthoringMetadata() {
        StrategyRegistry registry = new StrategyRegistryLoader().load(templateSource());

        assertEquals("frontend/src/capability-extensions/providers.tsx",
                registry.targets().get("frontend.capability-providers").path());
        assertEquals("ENSURE_IMPORT", registry.strategies().get("frontend.login.import-provider").type());
        assertEquals(10, registry.validators().get("login.postcondition").order());
        assertTrue(registry.targets().containsKey("backend.capability-webmvc"));
    }

    private static Path templateSource() {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null && !Files.isDirectory(current.resolve("template-source"))) current = current.getParent();
        if (current == null) throw new AssertionError("repository root");
        return current.resolve("template-source");
    }
}
