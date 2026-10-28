package com.devagentstudio.template.engine.service.appupdate;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** HTTP adapter for desktop app release checks; installation assets are served by the CDN. */
@RestController
public final class AppUpdateController {
    private final AppUpdateService service;

    public AppUpdateController(AppUpdateService service) { this.service = service; }

    /** Returns the configured release and, when eligible, the unchanged CDN base URL. */
    @PostMapping(value = "/v1/app-updates/check", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public AppUpdateResponse check(@RequestBody AppUpdateRequest request) {
        return service.check(request.getCurrentVersion());
    }
}
