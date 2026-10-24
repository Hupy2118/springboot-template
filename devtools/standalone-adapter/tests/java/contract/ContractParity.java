import com.devagentstudio.template.engine.core.v3.CodeTemplateRelease;
import com.devagentstudio.template.engine.core.v3.ExtensionRelease;
import com.devagentstudio.template.engine.source.v3.CodeTemplateReleaseLoader;
import java.nio.file.Paths;
import java.util.Arrays;

/** Use the production Engine Loader for semantic parity, without Engine edits. */
public final class ContractParity {
    public static void main(String[] args) throws Exception {
        CodeTemplateRelease nativeRelease = new CodeTemplateReleaseLoader(Paths.get(args[0])).load();
        CodeTemplateRelease standalone = new CodeTemplateReleaseLoader(Paths.get(args[1])).load();
        for (String id : Arrays.asList("login", "authorization")) {
            ExtensionRelease a = nativeRelease.extensions().get(id);
            ExtensionRelease b = standalone.extensions().get(id);
            equal(a.id(), b.id(), id + " id");
            equal(a.requires(), b.requires(), id + " requires");
            equal(a.applicationYaml(), b.applicationYaml(), id + " applicationYaml");
            equal(a.applicationProfileYaml(), b.applicationProfileYaml(), id + " applicationProfileYaml");
        }
        ExtensionRelease login = standalone.extensions().get("login");
        if (!login.applicationAnnotations().isEmpty() || !login.mavenDependencies().isEmpty()) {
            throw new AssertionError("private Login runtime contribution remains");
        }
        System.out.println("Engine Loader semantic contract parity: PASS");
    }
    private static void equal(Object a, Object b, String label) {
        if (!a.equals(b)) throw new AssertionError(label + " differs");
    }
}
