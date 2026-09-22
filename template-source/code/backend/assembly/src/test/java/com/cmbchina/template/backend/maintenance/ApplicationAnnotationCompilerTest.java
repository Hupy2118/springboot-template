package com.cmbchina.template.backend.maintenance;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationAnnotationCompilerTest {
    @Test
    void projectsAnnotationsOnceAndKeepsBaseFileUntouched() throws Exception {
        Path application = Files.createTempFile("Application", ".java");
        String base = "package example;\n\nimport org.springframework.boot.autoconfigure.SpringBootApplication;\n\n@SpringBootApplication\nclass Application {}\n";
        Files.write(application, base.getBytes(StandardCharsets.UTF_8));
        ApplicationAnnotationCompiler.apply(application, Arrays.asList("example.EnableLogin", "example.EnableLogin"));
        String assembled = new String(Files.readAllBytes(application), StandardCharsets.UTF_8);
        assertTrue(assembled.contains("import example.EnableLogin;"));
        assertTrue(assembled.contains("@EnableLogin\n@SpringBootApplication"));
        ApplicationAnnotationCompiler.apply(application, Arrays.asList("example.EnableLogin"));
        assertEquals(1, occurrences(new String(Files.readAllBytes(application), StandardCharsets.UTF_8), "@EnableLogin"));
        Files.deleteIfExists(application);
    }
    private static int occurrences(String value, String token) { return (value.length() - value.replace(token, "").length()) / token.length(); }
}
