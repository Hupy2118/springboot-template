package com.devagentstudio.template.engine.service.appupdate;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class AppUpdatePropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Settings.class);

    @Test
    void bindsTheDocumentedCamelCaseSettingsWithoutChangingTheUrl() {
        runner.withPropertyValues("devagentstudio.app-update.latestAppVersion=1.2.0+001",
                "devagentstudio.app-update.appDownloadUrl=https://cdn.example.com/releases/").run(context -> {
                    assertThat(context).hasNotFailed();
                    AppUpdateProperties properties = context.getBean(AppUpdateProperties.class);
                    assertThat(properties.isConfigured()).isTrue();
                    assertThat(properties.getLatestAppVersion()).isEqualTo("1.2.0+001");
                    assertThat(properties.getAppDownloadUrl()).isEqualTo("https://cdn.example.com/releases/");
                });
    }

    @Test
    void allowsBothPropertiesToBeAbsent() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AppUpdateProperties.class).isConfigured()).isFalse();
        });
    }

    @Test
    void acceptsHttpCdnBaseUrlsForLocalAndInternalDistribution() {
        for (String url : new String[] {"http://127.0.0.1:18081/", "http://s3gw.paasuat.com/"}) {
            runner.withPropertyValues("devagentstudio.app-update.latestAppVersion=1.0.1",
                    "devagentstudio.app-update.appDownloadUrl=" + url).run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(AppUpdateProperties.class).getAppDownloadUrl()).isEqualTo(url);
                    });
        }
    }

    @Test
    void rejectsPartialConfigurationAtStartup() {
        for (String setting : new String[] {"latestAppVersion=1.2.0", "appDownloadUrl=https://cdn.example.com"}) {
            runner.withPropertyValues("devagentstudio.app-update." + setting).run(context ->
                    assertThat(context).hasFailed().getFailure().hasRootCauseMessage(
                            "devagentstudio.app-update requires both latestAppVersion and appDownloadUrl"));
        }
    }

    @Test
    void rejectsInvalidVersionsAtStartup() {
        for (String version : new String[] {"", "1.2", "01.2.0", "1.2.0-01", " 1.2.0"}) {
            runner.withPropertyValues("devagentstudio.app-update.appDownloadUrl=https://cdn.example.com")
                    .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                            new MapPropertySource("version", Collections.<String, Object>singletonMap(
                                    "devagentstudio.app-update.latestAppVersion", version))))
                    .run(context ->
                    assertThat(context).hasFailed());
        }
    }

    @Test
    void rejectsInvalidCdnAddressesAtStartup() {
        for (String url : new String[] {"", "/downloads", "https:///downloads", "https://", "http:///downloads",
                "ftp://cdn.example.com", "https://cdn.example.com/bad path"}) {
            runner.withPropertyValues("devagentstudio.app-update.latestAppVersion=1.2.0",
                    "devagentstudio.app-update.appDownloadUrl=" + url).run(context ->
                    assertThat(context).hasFailed().getFailure().hasRootCauseInstanceOf(java.net.URISyntaxException.class));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AppUpdateProperties.class)
    static class Settings { }
}
