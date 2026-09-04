package com.xcodeagent.template.engine.service;

import com.xcodeagent.template.engine.core.CorePlanResult;
import com.xcodeagent.template.engine.core.RequestedConfig;
import com.xcodeagent.template.engine.core.TemplateEngine;
import com.xcodeagent.template.engine.core.TemplateState;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class EngineController {
    private final TemplateEngine core;
    private final TokenAuthenticator auth;
    private final PackageBuilder packages;

    EngineController(TemplateEngine core, TokenAuthenticator auth, com.fasterxml.jackson.databind.ObjectMapper json) {
        this.core = core; this.auth = auth; this.packages = new PackageBuilder(json);
    }

    @PostMapping(value = "/v1/plan", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_UTF8_VALUE)
    public Map<String, Object> plan(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                    @RequestBody Map<String, Object> request) {
        auth.require(authorization, "template.plan");
        requireOnly(request, "currentTemplateState", "requestedConfig");
        Object rawState = request.get("currentTemplateState");
        TemplateState current = rawState == null ? null : EngineMapper.state(rawState);
        RequestedConfig requested = EngineMapper.requested(request.get("requestedConfig"));
        return EngineMapper.plan(core.plan(current, requested));
    }

    @PostMapping(value = "/v1/generate", consumes = MediaType.APPLICATION_JSON_VALUE, produces = "application/zip")
    public ResponseEntity<byte[]> generate(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                           @RequestBody Map<String, Object> request) {
        auth.require(authorization, "template.generate");
        requireOnly(request, "requestedConfig");
        CorePlanResult result = core.plan(null, EngineMapper.requested(request.get("requestedConfig")));
        return zip(packages.generatedProject(result));
    }

    @PostMapping(value = "/v1/update", consumes = MediaType.APPLICATION_JSON_VALUE, produces = "application/zip")
    public ResponseEntity<byte[]> update(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                         @RequestBody Map<String, Object> request) {
        auth.require(authorization, "template.update");
        requireOnly(request, "currentTemplateState", "requestedConfig");
        if (request.get("currentTemplateState") == null) throw new ServiceException("BAD_REQUEST", "currentTemplateState is required", 400);
        CorePlanResult result = core.plan(EngineMapper.state(request.get("currentTemplateState")), EngineMapper.requested(request.get("requestedConfig")));
        if (result.kind() == CorePlanResult.Kind.NO_CHANGE) return ResponseEntity.noContent().build();
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
