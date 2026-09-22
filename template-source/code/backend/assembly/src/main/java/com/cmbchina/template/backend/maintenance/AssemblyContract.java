package com.cmbchina.template.backend.maintenance;

import java.nio.file.*;

public final class AssemblyContract {
    private AssemblyContract() { }
    public static void verify(Path workspace) {
        if (!Files.exists(workspace.resolve("pom.xml")) || !Files.exists(workspace.resolve("src/main/java/com/cmbchina/backend/Application.java")))
            throw new TemplateException("ASSEMBLY_CONTRACT_VIOLATION", "base Spring Boot files are missing");
        if (Files.exists(workspace.resolve("src/main/java/com/cmbchina/backend/common/config/CapabilityWebMvcConfiguration.java")))
            throw new TemplateException("ASSEMBLY_CONTRACT_VIOLATION", "legacy capability anchor was assembled");
    }
}
