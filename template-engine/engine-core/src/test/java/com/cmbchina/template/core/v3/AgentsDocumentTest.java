package com.cmbchina.template.core.v3;

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
    @Test void frontendSharedGoldensAndValidation() throws Exception {
        Path root = fixtures();
        byte[] base = Files.readAllBytes(root.resolve("frontend.md"));
        Map<String, byte[]> fragments = new LinkedHashMap<String, byte[]>();
        for (String profile : Arrays.asList("base", "login", "authorization")) {
            if (!"base".equals(profile)) fragments.put(profile, Files.readAllBytes(root.resolve(profile + ".fragment.md")));
            List<String> files = Files.readAllLines(root.resolve("frontend-" + profile + ".files.txt"), StandardCharsets.UTF_8);
            byte[] actual = AgentsDocument.composeFrontend(base, fragments, files);
            assertArrayEquals(Files.readAllBytes(root.resolve("frontend-" + profile + ".expected.md")), actual);
            Collections.reverse(files);
            assertArrayEquals(actual, AgentsDocument.composeFrontend(base, fragments, files));
        }
        for (String invalid : Arrays.asList("../escape", "/absolute", "src//bad", "src/./bad", "src/../bad", "src\\bad", "src/bad\npath"))
            assertThrows(RuntimeException.class, () -> AgentsDocument.composeFrontend(base, fragments, Collections.singletonList(invalid)));
        for (byte[] invalid : Arrays.asList(Files.readAllBytes(root.resolve("base.md")),
                (new String(base, StandardCharsets.UTF_8) + new String(base, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8)))
            assertThrows(RuntimeException.class, () -> AgentsDocument.composeFrontend(invalid, fragments, Collections.emptyList()));
    }

}
