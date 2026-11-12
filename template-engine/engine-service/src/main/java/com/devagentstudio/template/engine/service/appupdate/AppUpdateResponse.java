 package com.devagentstudio.template.engine.service.appupdate;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Release check result; the CDN base address is null whenever no update is offered.
 */
@Getter
@AllArgsConstructor
@JsonInclude
public class AppUpdateResponse {

    private final boolean updateAvailable;

    private final String latestAppVersion;

    private final String appDownloadUrl;
}
