package com.cmbchina.managedb.bootstrap;

import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.cmbchina.managedb.domain.AuthorizationSeedPort;
import com.cmbchina.managedb.domain.repository.DataOperationRepository;
import com.cmbchina.managedb.infrastructure.datasource.ConfiguredDatabaseSchemas;
import com.cmbchina.managedb.infrastructure.persistence.DataOperationCodec;
import com.cmbchina.managedb.infrastructure.persistence.MybatisDataOperationRepository;
import com.cmbchina.managedb.infrastructure.persistence.mapper.DatasourceRegistrationMapper;
import com.cmbchina.managedb.infrastructure.persistence.mapper.TableBindingMapper;
import com.cmbchina.managedb.infrastructure.sql.AuthorizationSeedJdbcAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** 将管理库审计与固定挂载的事务端口分别装配。 */
@Configuration
public class AuthorizationSeedConfiguration {
    /** 复用管理事务、资源行锁和服务主键生成器。 */
    @Bean
    public DataOperationRepository dataOperationRepository(DatasourceRegistrationMapper resources, TableBindingMapper tables,
            IdentifierGenerator ids, ObjectMapper mapper, JdbcTemplate jdbc, PlatformTransactionManager manager,
            @Value("${managed-database.registration.transaction-timeout-seconds:10}") int timeout) {
        return new MybatisDataOperationRepository(resources, tables, ids, new DataOperationCodec(mapper), jdbc, manager, timeout);
    }

    /** 复用固定挂载以及执行超时策略。 */
    @Bean
    public AuthorizationSeedPort authorizationSeedPort(ConfiguredDatabaseSchemas mounts,
            @Value("${managed-database.sql.execution-timeout-seconds:30}") int timeout) {
        return new AuthorizationSeedJdbcAdapter(mounts, timeout);
    }
}
