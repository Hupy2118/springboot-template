package com.xcodeagent.template.authoring;

import com.xcodeagent.template.engine.source.TemplateSourceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RoundTripVerifierTest {
    @TempDir Path temporaryDirectory;
    @Test void acceptsGeneratedProjectWithNormalizedLineEndings() throws Exception { Path workbench = new WorkbenchInitializer(source(), temporaryDirectory.resolve(".workbench")).initialize("draft-roundtrip", Collections.singletonList("login")); Path project = workbench.resolve("project"); Path readme = project.resolve("backend/README.md"); Files.write(readme, new String(Files.readAllBytes(readme), StandardCharsets.UTF_8).replace("\n", "\r\n").concat("\r\n").getBytes(StandardCharsets.UTF_8)); assertDoesNotThrow(() -> new RoundTripVerifier().verify(source(), project, "login")); }
    @Test void rejectsMissingOrChangedGeneratedFiles() throws Exception { Path workbench = new WorkbenchInitializer(source(), temporaryDirectory.resolve(".workbench")).initialize("draft-roundtrip", Collections.singletonList("login")); Files.write(workbench.resolve("project/backend/README.md"), "changed".getBytes(StandardCharsets.UTF_8)); assertThrows(TemplateSourceException.class, () -> new RoundTripVerifier().verify(source(), workbench.resolve("project"), "login")); }
    private static Path source() { Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath(); while (current != null && !Files.isDirectory(current.resolve("template-source"))) current = current.getParent(); if (current == null) throw new AssertionError("repo"); return current.resolve("template-source"); }
}
