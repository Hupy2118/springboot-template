package com.cmbchina.managedb.bootstrap;

import com.cmbchina.managedb.domain.RuntimeConnectionPort;
import com.cmbchina.managedb.infrastructure.datasource.ConfiguredRuntimeConnections;
import com.cmbchina.managedb.infrastructure.datasource.DatabaseMountProperties;
import com.cmbchina.managedb.infrastructure.datasource.RuntimeConnectionProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 组合根装配独立调试凭据，应用层只依赖领域端口。
 */
@Configuration
@EnableConfigurationProperties(RuntimeConnectionProperties.class)
public class RuntimeConnectionConfiguration {
    /**
     * 复用管理挂载的位置，不复用其账号密码。
     */
    @Bean
    public RuntimeConnectionPort runtimeConnectionPort(DatabaseMountProperties mounts, RuntimeConnectionProperties runtime) {
        return new ConfiguredRuntimeConnections(mounts, runtime);
    }
}
