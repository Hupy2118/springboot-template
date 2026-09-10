package com.xcodeagent.template.engine.service;

import com.xcodeagent.template.engine.core.v2.ReconcileDecisionEngine;
import com.xcodeagent.template.engine.core.v2.TemplateRelease;
import com.xcodeagent.template.engine.core.v2.TemplateStateV2;
import com.xcodeagent.template.engine.core.v2.UpdateResult;
import com.xcodeagent.template.engine.core.v2.V2ProjectGenerator;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Collections;

@RestController
public class EngineController {
    private final TokenAuthenticator auth;
    private final PackageBuilder packages;
    private final ReconcileDecisionEngine reconcile;
    private final TemplateRelease release;
    private final V2ProjectGenerator generator;

    EngineController(TokenAuthenticator auth, com.fasterxml.jackson.databind.ObjectMapper json,
                     ReconcileDecisionEngine reconcile, TemplateRelease release, TemplateEngineProperties properties, V2ProjectGenerator generator) {
        this.auth = auth; this.packages = new PackageBuilder(json, java.nio.file.Paths.get(properties.getSourceRoot()));
        this.reconcile = reconcile; this.release = release; this.generator = generator;
    }

    @PostMapping(value = "/v1/generate", consumes = MediaType.APPLICATION_JSON_VALUE, produces = "application/zip")
    public ResponseEntity<byte[]> generate(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                           @RequestBody Map<String, Object> request) {
        auth.require(authorization, "template.generate");
        requireOnly(request, "requestedConfig");
        TemplateStateV2 initial = new TemplateStateV2(release.revision(), release.digest(), Collections.<String, com.xcodeagent.template.engine.core.v2.CapabilityState>emptyMap(),
                Collections.<String, com.xcodeagent.template.engine.core.v2.CapabilityState>emptyMap(), Collections.<String, com.xcodeagent.template.engine.core.v2.AppliedAdditionState>emptyMap());
        UpdateResult bootstrap = reconcile.decide(initial, EngineMapper.requestedV2(request.get("requestedConfig")), ReconcileDecisionEngine.Mode.APPLY, release);
        return zip(packages.generatedProject(generator.generate(bootstrap.nextTemplateState()), bootstrap.nextTemplateState()));
    }

    @PostMapping(value = "/v1/update", consumes = MediaType.APPLICATION_JSON_VALUE, produces = "application/zip")
    public ResponseEntity<byte[]> update(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                         @RequestBody Map<String, Object> request) {
        auth.require(authorization, "template.update");
        requireOnly(request, "currentTemplateState", "requestedConfig", "mode");
        if (request.get("currentTemplateState") == null) throw new ServiceException("BAD_REQUEST", "currentTemplateState is required", 400);
        Object rawMode = request.get("mode");
        if (!(rawMode instanceof String)) throw new ServiceException("BAD_REQUEST", "mode must be APPLY or RECONCILE", 400);
        ReconcileDecisionEngine.Mode mode;
        try { mode = ReconcileDecisionEngine.Mode.valueOf((String) rawMode); }
        catch (IllegalArgumentException e) { throw new ServiceException("BAD_REQUEST", "mode must be APPLY or RECONCILE", 400); }
        UpdateResult result = reconcile.decide(EngineMapper.stateV2(request.get("currentTemplateState")), EngineMapper.requestedV2(request.get("requestedConfig")), mode, release);
        if (result.kind() == UpdateResult.Kind.NO_CHANGE) return ResponseEntity.noContent().build();
        return zip(packages.updatePackage(result));
    }

    private ResponseEntity<byte[]> zip(byte[] body) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip")).body(body);
    }
    private void requireOnly(Map<String, Object> request, String... fields) {
        if (request == null || request.size() != fields.length) throw new ServiceException("BAD_REQUEST", "request fields are invalid", 400);
        for (String field : fields) if (!request.containsKey(field)) throw new ServiceException("BAD_REQUEST", "request is missing " + field, 400);
    }
}
