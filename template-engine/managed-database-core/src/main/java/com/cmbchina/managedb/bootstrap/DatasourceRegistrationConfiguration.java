package com.cmbchina.managedb.bootstrap;

import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.cmbchina.managedb.domain.DatasourceKeyGenerator;
import com.cmbchina.managedb.domain.repository.DatasourceResourceRepository;
import com.cmbchina.managedb.infrastructure.datasource.ConfiguredDatabaseSchemas;
import com.cmbchina.managedb.infrastructure.datasource.DatabaseMountProperties;
import com.cmbchina.managedb.infrastructure.identity.HashDatasourceKeyGenerator;
import com.cmbchina.managedb.infrastructure.persistence.MybatisDatasourceResourceRepository;
import com.cmbchina.managedb.infrastructure.persistence.mapper.DatasourceRegistrationMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 各部署环境统一装配真实注册能力，数据库位置由环境配置提供。
 */
@Configuration
@MapperScan(basePackageClasses = DatasourceRegistrationMapper.class)
@EnableConfigurationProperties(DatabaseMountProperties.class)
public class DatasourceRegistrationConfiguration {
    /**
     * 由部署为每个实例提供不同机器组合，保证内部 bigint 主键唯一。
     */
    @Bean
    public IdentifierGenerator registrationIdentifierGenerator(
            @Value("${managed-database.id.worker-id}") long workerId,
            @Value("${managed-database.id.datacenter-id}") long datacenterId) {
        if (workerId < 0 || workerId > 31 || datacenterId < 0 || datacenterId > 31) {
            throw new IllegalArgumentException("主键生成机器标识必须处于 0 到 31");
        }
        return new DefaultIdentifierGenerator(workerId, datacenterId);
    }

    /**
     * 真实挂载池独立于管理库 DataSource，停机时统一关闭。
     */
    @Bean(destroyMethod = "close")
    public ConfiguredDatabaseSchemas configuredDatabaseSchemas(DatabaseMountProperties properties,
                                                               @Value("${managed-database.mount.timeout-millis:5000}") int timeoutMillis,
                                                               @Value("${managed-database.mount.ssl-mode:VERIFY_IDENTITY}") String sslMode,
                                                               @Value("${managed-database.sql.execution-timeout-seconds:30}") int sqlSeconds,
                                                               @Value("${managed-database.sql.metadata-timeout-seconds:5}") int metadataSeconds,
                                                               @Value("${managed-database.sql.network-timeout-millis:35000}") int networkMillis) {
        return new ConfiguredDatabaseSchemas(properties, timeoutMillis, sslMode, sqlSeconds, metadataSeconds, networkMillis);
    }

    /**
     * 装配纯 JDK 的固定长度编码生成器。
     */
    @Bean
    public DatasourceKeyGenerator datasourceKeyGenerator() {
        return new HashDatasourceKeyGenerator();
    }

    /**
     * 管理库事务只包围统计或单次写入，不持有目标连接。
     */
    @Bean
    public DatasourceResourceRepository datasourceResourceRepository(
            DatasourceRegistrationMapper mapper, IdentifierGenerator identifiers,
            PlatformTransactionManager manager,
            @Value("${managed-database.registration.transaction-timeout-seconds:10}") int timeout) {
        return new MybatisDatasourceResourceRepository(mapper, identifiers, manager, timeout);
    }

}
