package com.cmbchina.template.backend.maintenance;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentsDocumentTest {
    private Path fixtures() {
        Path root = Paths.get("").toAbsolutePath();
        while (!Files.isDirectory(root.resolve("scripts/ci/fixtures/agents-document"))) root = root.getParent();
        return root.resolve("scripts/ci/fixtures/agents-document");
    }
    @Test void sharedGoldenProfilesAndStrictValidation() throws Exception {
        Path root = fixtures();
        byte[] base = Files.readAllBytes(root.resolve("base.md"));
        Map<String, byte[]> fragments = new LinkedHashMap<String, byte[]>();
        assertArrayEquals(Files.readAllBytes(root.resolve("base.expected.md")), AgentsDocument.compose(base, fragments));
        for (String id : Arrays.asList("login", "authorization")) {
            byte[] fragment = Files.readAllBytes(root.resolve(id + ".fragment.md"));
            fragments.put(id, fragment);
            assertArrayEquals(Files.readAllBytes(root.resolve(id + ".expected.md")), AgentsDocument.compose(base, fragments));
            String text = new String(fragment, StandardCharsets.UTF_8);
            assertThrows(RuntimeException.class, () -> AgentsDocument.fragment(id, text.replace(":END", ":BROKEN").getBytes(StandardCharsets.UTF_8)));
            assertThrows(RuntimeException.class, () -> AgentsDocument.fragment(id, (text + text).getBytes(StandardCharsets.UTF_8)));
        }
        assertThrows(RuntimeException.class, () -> AgentsDocument.normalize(new byte[] {(byte) 0xff}));
        assertThrows(RuntimeException.class, () -> AgentsDocument.normalize(new byte[] {0}));
        assertThrows(RuntimeException.class, () -> AgentsDocument.fragment("../login", fragments.get("login")));
        assertThrows(RuntimeException.class, () -> AgentsDocument.compose(fragments.get("login"), fragments));
    }
}
