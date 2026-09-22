package com.cmbchina.template.backend.maintenance;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class PomDependencyCompilerTest {
    private static final MavenDependency LOGIN_AUTH = new MavenDependency("ZA21", "bee-starter-auth", null, null);

    @Test
    void projectsDependencyIdempotentlyAndNormalizesItAway() throws Exception {
        Path pom = Files.createTempFile("pom", ".xml");
        Path basePom = Files.createTempFile("base-pom", ".xml");
        String base = "<project><modelVersion>4.0.0</modelVersion><dependencies/></project>";
        Files.write(pom, base.getBytes(StandardCharsets.UTF_8));
        Files.write(basePom, base.getBytes(StandardCharsets.UTF_8));
        PomDependencyCompiler.apply(pom, Arrays.asList(LOGIN_AUTH));
        String first = read(pom);
        PomDependencyCompiler.apply(pom, Arrays.asList(LOGIN_AUTH));
        assertEquals(first, read(pom));
        assertTrue(read(pom).contains("bee-starter-auth"));
        assertEquals(PomDependencyCompiler.normalizedPom(pom, Arrays.asList(LOGIN_AUTH)),
                PomDependencyCompiler.normalizedPom(basePom, Arrays.<MavenDependency>asList()));
        Files.deleteIfExists(pom);
        Files.deleteIfExists(basePom);
    }

    @Test
    void rejectsModifiedGeneratedDependency() throws Exception {
        Path pom = Files.createTempFile("pom", ".xml");
        Files.write(pom, "<project><modelVersion>4.0.0</modelVersion><dependencies/></project>".getBytes(StandardCharsets.UTF_8));
        PomDependencyCompiler.apply(pom, Arrays.asList(LOGIN_AUTH));
        Files.write(pom, read(pom).replace("bee-starter-auth", "changed-artifact").getBytes(StandardCharsets.UTF_8));
        assertThrows(TemplateException.class, () -> PomDependencyCompiler.assertContributionsIntact(pom, Arrays.asList(LOGIN_AUTH)));
        Files.deleteIfExists(pom);
    }

    private static String read(Path file) throws Exception { return new String(Files.readAllBytes(file), StandardCharsets.UTF_8); }
}
