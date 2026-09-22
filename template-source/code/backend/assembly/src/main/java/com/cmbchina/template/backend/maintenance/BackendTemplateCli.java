package com.cmbchina.template.backend.maintenance;

import java.nio.file.*;
import java.util.*;

public final class BackendTemplateCli {
    private BackendTemplateCli() { }
    public static void main(String[] args) {
        try { execute(findRoot(), args); } catch (TemplateException e) { System.err.println(e.getMessage()); System.exit(2); }
    }
    static void execute(Path root, String[] args) {
        if(args.length == 0) throw new TemplateException("INVALID_COMMAND", usage());
        String command=args[0]; String profile=args.length > 1 ? args[1] : null;
        if("status".equals(command)) { status(root); return; }
        if("test-maintenance".equals(command)) { new MavenRunner(root).run(root.resolve("assembly/pom.xml"), "test"); return; }
        if(profile == null) throw new TemplateException("INVALID_COMMAND", usage());
        if("assemble".equals(command)) { new BackendAssembler(root).assemble(profile, false); return; }
        if("reset".equals(command)) { new BackendAssembler(root).assemble(profile, true); return; }
        if("sync".equals(command)) { new WorkspaceSync(root).sync(profile); return; }
        if("build".equals(command)) { new BackendAssembler(root).assemble(profile, true); new MavenRunner(root).run(root.resolve("workspace/pom.xml"), "verify"); return; }
        if("dev".equals(command)) { new BackendAssembler(root).assemble(profile, false); new MavenRunner(root).run(root.resolve("workspace/pom.xml"), "spring-boot:run"); return; }
        if("verify-update".equals(command)) { new VerifyUpdate(root).run(profile); return; }
        throw new TemplateException("INVALID_COMMAND", usage());
    }
    private static void status(Path root) {
        WorkspaceState state=WorkspaceState.read(root.resolve("workspace")); WorkspaceStatus.Report report=WorkspaceStatus.scan(root, state);
        System.out.println("Profile: " + state.profile); System.out.println("Selected Extensions: " + state.extensions); System.out.println("Edit Target: " + state.editTarget);
        print("Modified files", report.modified); print("New files", report.created); print("Deleted files", report.deleted); print("Generated-only differences", report.generated);
    }
    private static void print(String title, List<String> values) { System.out.println(title + ": " + (values.isEmpty() ? "none" : values)); }
    private static Path findRoot() { Path path=Paths.get("").toAbsolutePath(); while(path != null) { if(Files.exists(path.resolve("assembly/profiles.yaml"))) return path; path=path.getParent(); } throw new TemplateException("BACKEND_ROOT_NOT_FOUND", "run through backend-template"); }
    private static String usage() { return "backend-template <assemble|dev|sync|reset|build|status|verify-update|test-maintenance> [base|login|authorization|full]"; }
}
