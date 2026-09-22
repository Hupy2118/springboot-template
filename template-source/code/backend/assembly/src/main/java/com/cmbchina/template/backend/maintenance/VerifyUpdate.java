package com.cmbchina.template.backend.maintenance;

import java.nio.file.Path;
import java.util.*;

public final class VerifyUpdate {
    private final Path root;
    public VerifyUpdate(Path root) { this.root=root; }
    public void run(String changed) {
        List<String> profiles;
        if("base".equals(changed)) profiles=Arrays.asList("base", "login", "authorization", "full");
        else if("login".equals(changed)) profiles=Arrays.asList("login", "authorization", "full");
        else if("authorization".equals(changed)) profiles=Arrays.asList("authorization", "full");
        else throw new TemplateException("UNKNOWN_PROFILE", changed);
        for(String profile:profiles) { new BackendAssembler(root).assemble(profile, true); new MavenRunner(root).run(root.resolve("workspace/pom.xml"), "verify"); }
    }
}
