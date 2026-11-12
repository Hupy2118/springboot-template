package com.devagentstudio.template.engine.service.appupdate;

import com.devagentstudio.template.engine.service.ServiceException;
import org.springframework.stereotype.Service;

import java.net.URI;

/** Stateless app update policy, independent of template capabilities and state. */
@Service
public final class AppUpdateService {
    private final String latestAppVersion;
    private final String targetDownloadUrl;
    private final SemanticVersion target;

    /** 读取启动时校验过的发布配置并生成目标版本的 CDN 地址。 */
    public AppUpdateService(AppUpdateProperties properties) {
        latestAppVersion = properties.getLatestAppVersion();
        target = properties.isConfigured() ? SemanticVersion.parse(latestAppVersion) : null;
        targetDownloadUrl = target == null ? null : versionDirectory(properties.getAppDownloadUrl(), latestAppVersion);
    }

    /** 当前版本与目标版本的 SemVer 优先级不同时，返回目标版本的下载地址。 */
    public AppUpdateResponse check(String currentVersion) {
        SemanticVersion current;
        try {
            current = SemanticVersion.parse(currentVersion);
        } catch (IllegalArgumentException exception) {
            throw new ServiceException("BAD_REQUEST", "currentVersion must be valid SemVer 2.0.0", 400);
        }
        if (target == null) throw new ServiceException("APP_UPDATE_UNAVAILABLE", "app update release is not configured", 503);
        boolean available = current.compareTo(target) != 0;
        return new AppUpdateResponse(available, latestAppVersion, available ? targetDownloadUrl : null);
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
