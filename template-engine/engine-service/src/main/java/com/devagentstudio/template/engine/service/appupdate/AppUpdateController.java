package com.devagentstudio.template.engine.service.appupdate;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP adapter for desktop app release checks; installation assets are served by the CDN.
 */
@RestController
@RequiredArgsConstructor
public class AppUpdateController {

    private final AppUpdateService service;

    /**
     * 返回配置的目标版本以及需要切换时的 CDN 版本目录。
     */
    @PostMapping(value = "/v1/app-updates/check", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public AppUpdateResponse check(@RequestBody AppUpdateRequest request) {
        return service.check(request.getCurrentVersion());
    }
}
