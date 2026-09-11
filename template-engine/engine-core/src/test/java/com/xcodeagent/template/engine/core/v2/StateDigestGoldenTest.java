package com.xcodeagent.template.engine.core.v2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StateDigestGoldenTest {
    @Test
    void matchesTheFrozenCrossLanguageFixture() throws Exception {
        Path fixtureDirectory = repositoryRoot().resolve("validation/fixtures");
        ObjectMapper json = new ObjectMapper();
        Map<String, Object> state = json.readValue(Files.readAllBytes(fixtureDirectory.resolve("template-state-v2-golden.json")),
                new TypeReference<Map<String, Object>>() { });
        String expected = new String(Files.readAllBytes(fixtureDirectory.resolve("template-state-v2-golden.sha256")), "UTF-8").trim();
        assertEquals(expected, StateDigest.of(state));
    }

    private static Path repositoryRoot() {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null && !Files.isDirectory(current.resolve("template-source"))) current = current.getParent();
        if (current == null) throw new AssertionError("repository root");
        return current;
    }
}
