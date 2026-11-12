package com.devagentstudio.template.engine.service.appupdate;

import com.devagentstudio.template.engine.service.ServiceException;
import org.springframework.stereotype.Service;

/** Stateless app update policy, independent of template capabilities and state. */
@Service
public final class AppUpdateService {
    private final String latestAppVersion;
    private final String appDownloadUrl;
    private final SemanticVersion latest;

    public AppUpdateService(AppUpdateProperties properties) {
        latestAppVersion = properties.getLatestAppVersion();
        appDownloadUrl = properties.getAppDownloadUrl();
        latest = properties.isConfigured() ? SemanticVersion.parse(latestAppVersion) : null;
    }

    /** Offers newer versions while keeping stable clients off prerelease releases. */
    public AppUpdateResponse check(String currentVersion) {
        SemanticVersion current;
        try {
            current = SemanticVersion.parse(currentVersion);
        } catch (IllegalArgumentException exception) {
            throw new ServiceException("BAD_REQUEST", "currentVersion must be valid SemVer 2.0.0", 400);
        }
        if (latest == null) throw new ServiceException("APP_UPDATE_UNAVAILABLE", "app update release is not configured", 503);
        boolean available = current.compareTo(latest) < 0 && (current.isPrerelease() || !latest.isPrerelease());
        return new AppUpdateResponse(available, latestAppVersion, available ? appDownloadUrl : null);
    }
}
