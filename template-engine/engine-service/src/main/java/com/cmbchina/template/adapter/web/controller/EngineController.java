package com.cmbchina.template.adapter.web.controller;

import com.cmbchina.template.adapter.web.EngineMapper;
import com.cmbchina.template.core.v3.V3Exception;
import com.cmbchina.template.application.TemplateApplicationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** HTTP parsing and response adapter for the V3 endpoints. */
@RestController
@RequiredArgsConstructor
public class EngineController {
    private static final Set<String> GENERATE_FIELDS = new HashSet<String>(Arrays.asList("requestedConfig"));
    private static final Set<String> UPDATE_FIELDS = new HashSet<String>(
            Arrays.asList("protocolVersion", "currentTemplateState", "requestedConfig", "mode"));

    private final TemplateApplicationService templates;

    @PostMapping(value = "/v1/generate", consumes = MediaType.APPLICATION_JSON_VALUE, produces = "application/zip")
    public ResponseEntity<byte[]> generate(@RequestBody Map<String, Object> request) {
        requireOnly(request, GENERATE_FIELDS);
        Map<String, Object> requested = EngineMapper.requestedConfig(request.get("requestedConfig"));
        return zip(templates.generate(requested));
    }

    @PostMapping(value = "/v1/update", consumes = MediaType.APPLICATION_JSON_VALUE, produces = "application/zip")
    public ResponseEntity<byte[]> update(@RequestBody Map<String, Object> request) {
        requireOnly(request, UPDATE_FIELDS);
        Object protocolVersion = request.get("protocolVersion");
        if (!(protocolVersion instanceof String))
            throw bad("protocolVersion must be a string");
        if (!"3".equals(protocolVersion) && !"4".equals(protocolVersion))
            throw new V3Exception("PROTOCOL_VERSION_UNSUPPORTED", "protocolVersion must be 3 or 4", 400);
        Map<String, Object> current = EngineMapper.templateState(request.get("currentTemplateState"));
        Map<String, Object> requested = EngineMapper.requestedConfig(request.get("requestedConfig"));
        boolean v4 = "4".equals(protocolVersion);
        if (v4 && requested.containsKey("applicationConfig"))
            throw bad("V4 requestedConfig cannot contain applicationConfig");
        Object rawMode = request.get("mode");
        if (!(rawMode instanceof String) || !("APPLY".equals(rawMode) || "RECONCILE".equals(rawMode)))
            throw bad("mode must be APPLY or RECONCILE");
        return templates.update(current, requested, (String) rawMode, v4)
                .map(this::zip)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    private void requireOnly(Map<String, Object> request, Set<String> fields) {
        if (request == null || !request.keySet().equals(fields))
            throw bad("request fields are invalid");
    }

    private ResponseEntity<byte[]> zip(byte[] bytes) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip")).body(bytes);
    }

    private V3Exception bad(String message) {
        return new V3Exception("BAD_REQUEST", message, 400);
    }
}
