package com.devagentstudio.template.engine.service.appupdate;

import com.devagentstudio.template.engine.service.ServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Stateless app update policy, independent of template capabilities and state.
 */
@Service
@RequiredArgsConstructor
public class AppUpdateService {

    private final AppUpdateProperties properties;

    /**
     * Offers newer versions while keeping stable clients off prerelease releases.
     */
    public AppUpdateResponse check(String currentVersion) {
        SemanticVersion current;
        try {
            current = SemanticVersion.parse(currentVersion);
        } catch (IllegalArgumentException exception) {
            throw new ServiceException("BAD_REQUEST", "currentVersion must be valid SemVer 2.0.0", 400);
        }
        String latestVersion = properties.getLatestAppVersion();
        SemanticVersion latest = properties.isConfigured() ? SemanticVersion.parse(latestVersion) : null;
        if (latest == null) {
            throw new ServiceException("APP_UPDATE_UNAVAILABLE", "app update release is not configured", 503);
        }
        boolean available = current.compareTo(latest) < 0 && (current.isPrerelease() || !latest.isPrerelease());
        return new AppUpdateResponse(available, latestVersion, available ? properties.getAppDownloadUrl() : null);
    }
}
