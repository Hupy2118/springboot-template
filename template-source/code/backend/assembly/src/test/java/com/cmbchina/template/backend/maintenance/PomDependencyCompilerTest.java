package com.cmbchina.template.backend.maintenance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class PomDependencyCompilerTest {
    @TempDir
    Path directory;
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

    @Test
    void rejectsExternalEntityAndLeavesPomUnchanged() throws Exception {
        Path secret = directory.resolve("secret.txt");
        Files.write(secret, "XXE_SENTINEL".getBytes(StandardCharsets.UTF_8));
        assertDoctypeRejected("<!DOCTYPE project [<!ENTITY external SYSTEM \"" + secret.toUri()
                + "\">]><project><name>&external;</name></project>");
    }

    @Test
    void rejectsExternalDtdAndInternalEntityExpansion() throws Exception {
        Path dtd = directory.resolve("external.dtd");
        Files.write(dtd, "<!ENTITY external 'XXE_SENTINEL'>".getBytes(StandardCharsets.UTF_8));
        assertDoctypeRejected("<!DOCTYPE project SYSTEM \"" + dtd.toUri()
                + "\"><project><name>&external;</name></project>");
        assertDoctypeRejected("<!DOCTYPE project [<!ENTITY a 'expanded'><!ENTITY b '&a;&a;'>]>"
                + "<project><name>&b;</name></project>");
    }

    private void assertDoctypeRejected(String xml) throws Exception {
        Path pom = directory.resolve("pom.xml");
        Files.write(pom, xml.getBytes(StandardCharsets.UTF_8));
        TemplateException applyError = assertThrows(TemplateException.class,
                () -> PomDependencyCompiler.apply(pom, Arrays.asList(LOGIN_AUTH)));
        assertTrue(applyError.getMessage().startsWith("POM_DEPENDENCY_COMPILATION_FAILED:"));
        assertEquals(xml, read(pom));
        assertThrows(TemplateException.class,
                () -> PomDependencyCompiler.normalizedPom(pom, Arrays.asList(LOGIN_AUTH)));
        assertThrows(TemplateException.class,
                () -> PomDependencyCompiler.assertContributionsIntact(pom, Arrays.asList(LOGIN_AUTH)));
    }

    private static String read(Path file) throws Exception { return new String(Files.readAllBytes(file), StandardCharsets.UTF_8); }
}
