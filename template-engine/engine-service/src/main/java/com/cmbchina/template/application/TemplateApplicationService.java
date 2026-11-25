package com.cmbchina.template.application;

import com.cmbchina.template.core.v3.ApplicationConfigProjector;
import com.cmbchina.template.core.v3.ExtensionResolver;
import com.cmbchina.template.core.v3.V3ProjectMaterializer;
import com.cmbchina.template.core.v3.V3UpdatePlanner;
import com.cmbchina.template.core.v3.V3UpdateResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class TemplateApplicationService {
    private final ExtensionResolver resolver;
    private final V3ProjectMaterializer materializer;
    private final V3UpdatePlanner planner;
    private final TemplatePackageWriter packages;

    public byte[] generate(Map<String, Object> requested) {
        @SuppressWarnings("unchecked")
        Map<String, Object> suppliedApplication = (Map<String, Object>) requested.remove("applicationConfig");
        ExtensionResolver.ResolvedExtensions resolved = resolver.resolve(requested);
        Map<String, Object> application = ApplicationConfigProjector.resolve(suppliedApplication, resolved.effective());
        Map<String, byte[]> project = materializer.materialize(resolved, application);
        Map<String, Object> state = materializer.templateState(resolved, application);
        return packages.generatedProject(project, state);
    }

    public Optional<byte[]> update(Map<String, Object> current, Map<String, Object> requested, String mode, boolean v4) {
        @SuppressWarnings("unchecked")
        Map<String, Object> suppliedApplication = (Map<String, Object>) requested.remove("applicationConfig");
        ExtensionResolver.ResolvedExtensions resolved = resolver.resolve(requested);
        @SuppressWarnings("unchecked")
        Map<String, Object> previousApplication = (Map<String, Object>) current.get("managedConfiguration");
        Map<String, Object> application = suppliedApplication == null ? previousApplication
                : ApplicationConfigProjector.resolve(suppliedApplication, resolved.effective());
        V3UpdateResult result = v4 ? planner.planCapabilities(current, resolved, mode)
                : planner.plan(current, resolved, mode, application);
        if (result.kind() == V3UpdateResult.Kind.NO_CHANGE) {
            return Optional.empty();
        }
        return Optional.of(v4 ? packages.updatePackageV4(result) : packages.updatePackage(result));
    }
}
