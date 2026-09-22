package com.cmbchina.template.backend.maintenance;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

public final class MavenRunner {
    private final Path root;
    public MavenRunner(Path root) { this.root=root; }
    public void run(Path pom, String goal) {
        boolean windows=System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        List<String> command=Arrays.asList(root.resolve(windows ? "mvnw.cmd" : "mvnw").toString(), "-f", pom.toString(), goal);
        try { if(new ProcessBuilder(command).inheritIO().start().waitFor()!=0) throw new TemplateException("MAVEN_FAILED", goal); }
        catch(IOException e) { throw new TemplateException("MAVEN_FAILED", e.getMessage()); } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new TemplateException("MAVEN_INTERRUPTED", goal); }
    }
}
