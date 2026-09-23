package com.devagentstudio.template.engine.service;

import com.devagentstudio.template.engine.core.v3.CodeTemplateRelease;
import com.devagentstudio.template.engine.core.v3.ExtensionResolver;
import com.devagentstudio.template.engine.core.v3.V3Exception;
import com.devagentstudio.template.engine.core.v3.V3ProjectMaterializer;
import com.devagentstudio.template.engine.core.v3.V3UpdatePlanner;
import com.devagentstudio.template.engine.core.v3.V3UpdateResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** HTTP parsing and response adapter for the V3 Preview endpoints. */
@RestController
public class NextEngineController {
    private static final Set<String> GENERATE_FIELDS = new HashSet<String>(Arrays.asList("requestedConfig"));
    private static final Set<String> UPDATE_FIELDS = new HashSet<String>(Arrays.asList("protocolVersion", "currentTemplateState", "requestedConfig", "mode"));

    private final ObjectMapper json;
    private final CodeTemplateRelease release;
    private final ExtensionResolver resolver;
    private final V3ProjectMaterializer materializer;
    private final V3UpdatePlanner planner;
    private final NextPackageBuilder packages;

    NextEngineController(ObjectMapper json, CodeTemplateRelease release, ExtensionResolver resolver,
                         V3ProjectMaterializer materializer, V3UpdatePlanner planner) {
        this.json = json; this.release = release; this.resolver = resolver; this.materializer = materializer; this.planner = planner;
        this.packages = new NextPackageBuilder(json, release);
    }

    @PostMapping(value = "/v1/generate-next", consumes = MediaType.APPLICATION_JSON_VALUE, produces = "application/zip")
    public ResponseEntity<byte[]> generate(@RequestBody Map<String, Object> request) {
        requireOnly(request, GENERATE_FIELDS);
        Map<String, Object> requested = NextEngineMapper.requestedConfig(request.get("requestedConfig"));
        ExtensionResolver.ResolvedExtensions resolved = resolver.resolve(requested);
        Map<String, byte[]> project = materializer.materialize(resolved);
        Map<String, Object> state = materializer.templateState(resolved);
        return zip(packages.generatedProject(project, state));
    }

    @PostMapping(value = "/v1/update-next", consumes = MediaType.APPLICATION_JSON_VALUE, produces = "application/zip")
    public ResponseEntity<byte[]> update(@RequestBody Map<String, Object> request) {
        requireOnly(request, UPDATE_FIELDS);
        Object protocolVersion = request.get("protocolVersion");
        if (!(protocolVersion instanceof String)) throw bad("protocolVersion must be a string");
        if (!"3".equals(protocolVersion)) throw new V3Exception("PROTOCOL_VERSION_UNSUPPORTED", "protocolVersion must be 3", 400);
        Map<String, Object> current = NextEngineMapper.templateState(request.get("currentTemplateState"));
        Map<String, Object> requested = NextEngineMapper.requestedConfig(request.get("requestedConfig"));
        Object rawMode = request.get("mode");
        if (!(rawMode instanceof String) || !("APPLY".equals(rawMode) || "RECONCILE".equals(rawMode)))
            throw bad("mode must be APPLY or RECONCILE");
        ExtensionResolver.ResolvedExtensions resolved = resolver.resolve(requested);
        V3UpdateResult result = planner.plan(current, resolved, (String) rawMode);
        if (result.kind() == V3UpdateResult.Kind.NO_CHANGE) return ResponseEntity.noContent().build();
        return zip(packages.updatePackage(result));
    }

    private void requireOnly(Map<String, Object> request, Set<String> fields) {
        if (request == null || !request.keySet().equals(fields)) throw bad("request fields are invalid");
    }
    private ResponseEntity<byte[]> zip(byte[] bytes) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip")).body(bytes);
    }
    private V3Exception bad(String message) { return new V3Exception("BAD_REQUEST", message, 400); }
}
