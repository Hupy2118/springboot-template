package com.cmbchina.managedb.bootstrap;

import com.cmbchina.managedb.domain.TableDataQueryPort;
import com.cmbchina.managedb.infrastructure.datasource.ConfiguredDatabaseSchemas;
import com.cmbchina.managedb.infrastructure.sql.MySqlTableDataQuery;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 查询端口独立装配，复用固定挂载池，不新增连接配置或管理表。
 */
@Configuration
public class TableDataQueryConfiguration {
    /**
     * 只装配真实 JDBC 查询能力，不提供空结果替身。
     */
    @Bean
    public TableDataQueryPort tableDataQueryPort(ConfiguredDatabaseSchemas mounts,
                                                 @Value("${managed-database.query.timeout-seconds:5}") int timeoutSeconds) {
        return new MySqlTableDataQuery(mounts, timeoutSeconds);
    }
}
