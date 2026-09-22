package com.cmbchina.template.backend.maintenance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class ApplicationAnnotationCompiler {
    public static void apply(Path application, Collection<String> classes) {
        try {
            String source = new String(Files.readAllBytes(application), StandardCharsets.UTF_8);
            LinkedHashSet<String> annotations = new LinkedHashSet<String>(classes);
            for (String fqcn : annotations) {
                String simple = fqcn.substring(fqcn.lastIndexOf('.') + 1);
                String importLine = "import " + fqcn + ";";
                if (!source.contains(importLine)) source = insertImport(source, importLine);
                String annotation = "@" + simple;
                if (!source.contains(annotation)) source = source.replace("@SpringBootApplication", annotation + "\n@SpringBootApplication");
            }
            Files.write(application, source.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) { throw new TemplateException("APPLICATION_ANNOTATION_COMPILATION_FAILED", e.getMessage()); }
    }
    public static void assertContributionsIntact(Path application, Collection<String> classes) {
        try {
            String source = new String(Files.readAllBytes(application), StandardCharsets.UTF_8);
            for (String fqcn : new LinkedHashSet<String>(classes)) {
                String simple = fqcn.substring(fqcn.lastIndexOf('.') + 1);
                if (count(source, "import " + fqcn + ";") != 1 || count(source, "@" + simple) != 1)
                    throw new TemplateException("GENERATED_CONTRIBUTION_MODIFIED", "application annotation " + fqcn);
            }
        } catch (IOException e) { throw new TemplateException("APPLICATION_ANNOTATION_COMPILATION_FAILED", e.getMessage()); }
    }
    private static int count(String value, String token) { return (value.length() - value.replace(token, "").length()) / token.length(); }
    private static String insertImport(String source, String line) {
        int last = source.lastIndexOf("import ");
        if (last < 0) { int packageEnd = source.indexOf(';'); return source.substring(0, packageEnd + 1) + "\n\n" + line + source.substring(packageEnd + 1); }
        int end = source.indexOf('\n', last); return source.substring(0, end + 1) + line + "\n" + source.substring(end + 1);
    }
}
