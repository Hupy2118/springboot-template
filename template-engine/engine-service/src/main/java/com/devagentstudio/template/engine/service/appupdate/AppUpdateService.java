package com.devagentstudio.template.engine.service.appupdate;

import com.devagentstudio.template.engine.service.ServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.net.URI;

/**
 * Stateless app update policy, independent of template capabilities and state.
 */
@Service
@RequiredArgsConstructor
public class AppUpdateService {

    private final AppUpdateProperties properties;

    /**
     * 当前版本与目标版本的 SemVer 优先级不同时，返回目标版本目录。
     */
    public AppUpdateResponse check(String currentVersion) {
        SemanticVersion current;
        try {
            current = SemanticVersion.parse(currentVersion);
        } catch (IllegalArgumentException exception) {
            throw new ServiceException("BAD_REQUEST", "currentVersion must be valid SemVer 2.0.0", 400);
        }
        if (!properties.isConfigured()) {
            throw new ServiceException("APP_UPDATE_UNAVAILABLE", "app update release is not configured", 503);
        }
        String targetVersion = properties.getLatestAppVersion();
        SemanticVersion target = SemanticVersion.parse(targetVersion);
        boolean available = current.compareTo(target) != 0;
        String downloadUrl = available ? versionDirectory(properties.getAppDownloadUrl(), targetVersion) : null;
        return new AppUpdateResponse(available, targetVersion, downloadUrl);
    }

    /** 将已校验的版本号追加到原始路径，保留 URL 各部分的编码。 */
    private static String versionDirectory(String baseUrl, String version) {
        URI base = URI.create(baseUrl);
        String path = base.getRawPath();
        StringBuilder url = new StringBuilder(base.getScheme()).append("://")
                .append(base.getRawAuthority()).append(path);
        if (!path.endsWith("/")) url.append('/');
        url.append(version).append('/');
        if (base.getRawQuery() != null) url.append('?').append(base.getRawQuery());
        if (base.getRawFragment() != null) url.append('#').append(base.getRawFragment());
        return url.toString();
    }
}
