package com.devagentstudio.template.engine.service.appupdate;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Release check result; the CDN base address is null whenever no update is offered. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public final class AppUpdateResponse {
    private final boolean updateAvailable;
    private final String latestAppVersion;
    private final String appDownloadUrl;

    AppUpdateResponse(boolean updateAvailable, String latestAppVersion, String appDownloadUrl) {
        this.updateAvailable = updateAvailable;
        this.latestAppVersion = latestAppVersion;
        this.appDownloadUrl = appDownloadUrl;
    }

    public boolean isUpdateAvailable() { return updateAvailable; }
    public String getLatestAppVersion() { return latestAppVersion; }
    public String getAppDownloadUrl() { return appDownloadUrl; }
}
