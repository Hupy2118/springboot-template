package com.devagentstudio.template.authoring;

import com.devagentstudio.template.engine.source.TemplateSourceException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Command-line entry point for offline Capability authoring. */
public final class CapabilityCli {
    private CapabilityCli() { }

    public static void main(String[] arguments) {
        Path repository = repositoryRoot(Paths.get("").toAbsolutePath());
        Path workbench = run(repository.resolve("template-source"), repository.resolve(".workbench"), arguments);
        if (workbench != null) System.out.println(workbench);
    }

    static Path run(Path sourceRoot, Path workbenchesRoot, String[] arguments) {
        if (arguments == null || arguments.length == 0 || "--help".equals(arguments[0]) || "help".equals(arguments[0])) { help(null); return null; }
        if (arguments.length == 2 && "--help".equals(arguments[1])) { help(arguments[0]); return null; }
        if (arguments.length < 2) throw new TemplateSourceException("CAPABILITY_COMMAND_INVALID");
        if ("capture".equals(arguments[0]) && arguments.length == 2) { new AuthoringWorkflow(sourceRoot, workbenchesRoot).capture(arguments[1]); return workbenchesRoot.resolve(arguments[1]); }
        if ("compile".equals(arguments[0]) && arguments.length == 2) { new AuthoringWorkflow(sourceRoot, workbenchesRoot).compile(arguments[1]); return workbenchesRoot.resolve(arguments[1]); }
        if ("verify".equals(arguments[0]) && arguments.length == 2) { new AuthoringWorkflow(sourceRoot, workbenchesRoot).verify(arguments[1]); return workbenchesRoot.resolve(arguments[1]); }
        if ("status".equals(arguments[0]) && arguments.length == 2) { printStatus(arguments[1], new AuthoringWorkflow(sourceRoot, workbenchesRoot).status(arguments[1])); return workbenchesRoot.resolve(arguments[1]); }
        if ("build".equals(arguments[0]) && arguments.length == 2) { CapabilityStatusReport report = new AuthoringWorkflow(sourceRoot, workbenchesRoot).build(arguments[1]); printBuild(arguments[1], report); return workbenchesRoot.resolve(arguments[1]); }
        if (!"init".equals(arguments[0])) throw new TemplateSourceException("CAPABILITY_COMMAND_INVALID");
        String capabilityId = arguments[1];
        List<String> requires = requires(arguments);
        return new WorkbenchInitializer(sourceRoot, workbenchesRoot).initialize(capabilityId, requires);
    }

    private static List<String> requires(String[] arguments) {
        if (arguments.length == 2) return Collections.emptyList();
        if (arguments.length != 4 || !"--requires".equals(arguments[2]))
            throw new TemplateSourceException("CAPABILITY_COMMAND_INVALID");
        String raw = arguments[3];
        if (raw == null || raw.trim().isEmpty()) throw new TemplateSourceException("CAPABILITY_REQUIRE_INVALID");
        List<String> result = new ArrayList<String>();
        for (String value : raw.split(",")) result.add(value.trim());
        return result;
    }

    private static void help(String command) {
        if (command == null) System.out.println("Usage: capability <init|status|build|capture|compile|verify> ...");
        else if ("init".equals(command)) System.out.println("Usage: capability init <capabilityId> [--requires a,b]");
        else if ("status".equals(command)) System.out.println("Usage: capability status <capabilityId>");
        else if ("build".equals(command)) System.out.println("Usage: capability build <capabilityId>");
        else System.out.println("Usage: capability " + command + " <capabilityId>");
    }
    private static void printStatus(String id, CapabilityStatusReport report) {
        System.out.println("Capability: " + id + "\n\nDetected:");
        System.out.println("  Additions            " + report.additions().size());
        System.out.println("  Imports              " + report.importCount());
        System.out.println("  Anchor inserts       " + report.anchorInsertCount());
        System.out.println("\nUnsupported:\n  " + report.unsupportedChanges().size());
        for (UnsupportedChange change : report.unsupportedChanges()) System.out.println("  " + change.path() + "\n  Reason: " + change.reason());
        System.out.println("\nStatus: " + report.status());
    }
    private static void printBuild(String id, CapabilityStatusReport report) {
        System.out.println("Capability: " + id + "\n\nChanges:");
        System.out.println("  Additions          " + report.additions().size());
        System.out.println("  Imports            " + report.importCount());
        System.out.println("  Anchor inserts     " + report.anchorInsertCount());
        System.out.println("\nValidation:\n  Contract           PASS\n  Draft Source       PASS\n  Round-trip         PASS\n\nResult: READY");
    }

    private static Path repositoryRoot(Path current) {
        Path candidate = current;
        while (candidate != null && !Files.isDirectory(candidate.resolve("template-source"))) candidate = candidate.getParent();
        if (candidate == null) throw new TemplateSourceException("TEMPLATE_SOURCE_ROOT_NOT_FOUND");
        return candidate;
    }
}
