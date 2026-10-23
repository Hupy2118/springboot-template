package com.cmbchina.backend.standalone.login;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class MockLoginConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MockLoginConfiguration.class, MockLoginController.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withPropertyValues("server.address=127.0.0.1", "spring.profiles.active=local",
                    "standalone.mock.enabled=true");

    @Test void localAndDevRegisterMockWithoutCredentials() {
        for (String profile : new String[] {"local", "dev"})
            runner.withPropertyValues("spring.profiles.active=" + profile).run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(MockIdentityService.class)
                        .hasSingleBean(MockLoginController.class).hasBean("mockIdentityFilter");
            });
    }

    @Test void explicitDisableAndOtherProfilesDoNotRegisterMock() {
        runner.withPropertyValues("standalone.mock.enabled=false").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(MockIdentityService.class)
                    .doesNotHaveBean(MockLoginController.class).doesNotHaveBean("mockIdentityFilter");
        });
        runner.withPropertyValues("spring.profiles.active=prod").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(MockIdentityService.class)
                    .doesNotHaveBean(MockLoginController.class).doesNotHaveBean("mockIdentityFilter");
        });
    }

    @Test void publicBindingCannotStartMock() {
        runner.withPropertyValues("server.address=0.0.0.0").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage("Mock login requires explicit loopback server.address");
        });
    }
}
