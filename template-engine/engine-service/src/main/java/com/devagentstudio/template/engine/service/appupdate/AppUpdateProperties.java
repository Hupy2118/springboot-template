package com.devagentstudio.template.engine.service.appupdate;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.net.URISyntaxException;

/** Optional app release configuration; a supplied release must be complete and valid. */
@ConfigurationProperties(prefix = "devagentstudio.app-update")
public class AppUpdateProperties implements InitializingBean {
    private String latestAppVersion;
    private String appDownloadUrl;

    public String getLatestAppVersion() { return latestAppVersion; }
    public void setLatestAppVersion(String latestAppVersion) { this.latestAppVersion = latestAppVersion; }
    public String getAppDownloadUrl() { return appDownloadUrl; }
    public void setAppDownloadUrl(String appDownloadUrl) { this.appDownloadUrl = appDownloadUrl; }

    /** Missing settings disable checks without preventing template engine startup. */
    public boolean isConfigured() { return latestAppVersion != null && appDownloadUrl != null; }

    /** Rejects partial or malformed release settings during Spring bean initialization. */
    @Override
    public void afterPropertiesSet() {
        if (latestAppVersion == null && appDownloadUrl == null) return;
        if (!isConfigured()) throw new IllegalArgumentException(
                "devagentstudio.app-update requires both latestAppVersion and appDownloadUrl");
        try {
            SemanticVersion.parse(latestAppVersion);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("devagentstudio.app-update.latestAppVersion must be valid SemVer 2.0.0", exception);
        }
        try {
            URI url = new URI(appDownloadUrl);
            if ((!"https".equalsIgnoreCase(url.getScheme()) && !"http".equalsIgnoreCase(url.getScheme())) || url.getHost() == null)
                throw new URISyntaxException(appDownloadUrl, "HTTP(S) host required");
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("devagentstudio.app-update.appDownloadUrl must be an absolute HTTP(S) URL with a host", exception);
        }
    }
}
