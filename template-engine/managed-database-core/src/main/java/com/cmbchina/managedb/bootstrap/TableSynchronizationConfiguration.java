package com.cmbchina.managedb.bootstrap;

import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.cmbchina.managedb.domain.TableStructureComparator;
import com.cmbchina.managedb.domain.TableSyncSettings;
import com.cmbchina.managedb.domain.repository.TableOperationRepository;
import com.cmbchina.managedb.infrastructure.persistence.MybatisTableOperationRepository;
import com.cmbchina.managedb.infrastructure.persistence.TableOperationCodec;
import com.cmbchina.managedb.infrastructure.persistence.mapper.DatasourceRegistrationMapper;
import com.cmbchina.managedb.infrastructure.persistence.mapper.TableBindingMapper;
import com.cmbchina.managedb.infrastructure.persistence.mapper.TableOperationMapper;
import com.cmbchina.managedb.infrastructure.sql.BoundedTableTaskDispatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 组合根只装配基础设施及策略，应用服务交给 Service 构造注入。
 */
@Configuration
public class TableSynchronizationConfiguration {
    /**
     * 解析与校验共享的有限输入策略。
     */
    @Bean
    public TableSyncSettings tableSyncSettings(@Value("${managed-database.sql.max-sql-bytes:65536}") int sqlBytes,
                                               @Value("${managed-database.sql.max-check-tables:200}") int maxTables,
                                               @Value("${managed-database.sql.max-check-bytes:4194304}") int totalBytes) {
        return new TableSyncSettings(sqlBytes, maxTables, totalBytes);
    }

    /**
     * 纯 Java 比较器没有框架持久化依赖。
     */
    @Bean
    public TableStructureComparator tableStructureComparator() {
        return new TableStructureComparator();
    }

    /**
     * 有界线程池不接管历史操作自动恢复。
     */
    @Bean(destroyMethod = "close")
    public BoundedTableTaskDispatcher tableTaskDispatcher(@Value("${managed-database.sql.workers:2}") int workers) {
        return new BoundedTableTaskDispatcher(workers);
    }

    /**
     * 三张管理表的受控事务由同一仓储实现。
     */
    @Bean
    public TableOperationRepository tableOperationRepository(DatasourceRegistrationMapper resources, TableBindingMapper tables,
                                                             TableOperationMapper operations, IdentifierGenerator identifiers, ObjectMapper mapper,
                                                             PlatformTransactionManager manager,
                                                             @Value("${managed-database.registration.transaction-timeout-seconds:10}") int seconds) {
        return new MybatisTableOperationRepository(resources, tables, operations, identifiers, new TableOperationCodec(mapper), manager, seconds);
    }
}
