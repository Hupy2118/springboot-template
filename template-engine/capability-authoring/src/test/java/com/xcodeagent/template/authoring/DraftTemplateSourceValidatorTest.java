package com.xcodeagent.template.authoring;

import com.xcodeagent.template.engine.source.TemplateSourceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DraftTemplateSourceValidatorTest {
    @TempDir Path temporaryDirectory;

    @Test
    void validatesAReleaseWithTheUnchangedPublishedLoader() throws Exception {
        Path source = copy(templateSource());
        String revision = new String(Files.readAllBytes(source.resolve("template-revision.txt")), StandardCharsets.UTF_8);

        assertEquals("draft-validation", new DraftTemplateSourceValidator().validate(source).revision());
        assertEquals(revision, new String(Files.readAllBytes(source.resolve("template-revision.txt")), StandardCharsets.UTF_8));
    }

    @Test
    void rejectsInvalidDraftRegistryEntriesWithoutDigestBypass() throws Exception {
        Path source = copy(templateSource());
        Files.write(source.resolve("strategy-registry-v2.yaml"), ("\n  - { id: invalid, targetId: missing, type: ENSURE_IMPORT, order: 9999, parameters: { importStatement: x } }\n").getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.APPEND);

        assertThrows(TemplateSourceException.class, () -> new DraftTemplateSourceValidator().validate(source));
    }

    private Path copy(Path source) throws Exception { Path destination = temporaryDirectory.resolve("template-source"); java.util.stream.Stream<Path> paths = Files.walk(source); try { paths.forEach(path -> { try { Path target = destination.resolve(source.relativize(path).toString()); if (Files.isDirectory(path)) Files.createDirectories(target); else Files.copy(path, target); } catch (Exception e) { throw new RuntimeException(e); } }); } finally { paths.close(); } return destination; }
    private static Path templateSource() { Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath(); while (current != null && !Files.isDirectory(current.resolve("template-source"))) current = current.getParent(); if (current == null) throw new AssertionError("repository root"); return current.resolve("template-source"); }
}
