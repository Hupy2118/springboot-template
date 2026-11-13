package com.cmbchina.managedb.infrastructure.persistence;

import com.cmbchina.managedb.domain.AuthorizationSeedPayload;
import com.cmbchina.managedb.domain.DataOperation;
import com.cmbchina.managedb.domain.ResourceErrorCode;
import com.cmbchina.managedb.domain.repository.DataOperationRepository;
import com.cmbchina.managedb.infrastructure.persistence.mapper.DatasourceRegistrationMapper;
import com.cmbchina.managedb.infrastructure.persistence.mapper.TableBindingMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.*;

import static com.cmbchina.managedb.AuthorizationSeedFixture.*;
import static org.junit.jupiter.api.Assertions.*;

/** 隔离管理库已手工部署四张表时，验证真实资源锁、唯一受理和未知占用。 */
@EnabledIfEnvironmentVariable(named = "MANAGED_DB_SEED_TEST_MANAGEMENT_JDBC_URL", matches = ".+")
class DataOperationMysqlTest {
    /** 并发两连接争抢同键，只返回一个新操作；DDL 使用同一占用更新条件。 */
    @Test
    void serializesSameKeyAndRetainsUnknownAcrossRepositoryInstances() throws Exception {
        String key = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        AuthorizationSeedPayload payload = parse(key, json(key));
        AbstractDataSource dataSource = new AbstractDataSource() {
            /** 仅从测试环境获取管理库账号。 */
            @Override
            public Connection getConnection() throws SQLException {
                return getConnection(System.getenv("MANAGED_DB_SEED_TEST_MANAGEMENT_USER"),
                        System.getenv("MANAGED_DB_SEED_TEST_MANAGEMENT_PASSWORD"));
            }

            /** 连接错误不回显 URL 或账号。 */
            @Override
            public Connection getConnection(String user, String password) throws SQLException {
                try {
                    return DriverManager.getConnection(System.getenv("MANAGED_DB_SEED_TEST_MANAGEMENT_JDBC_URL"), user, password);
                } catch (SQLException exception) {
                    throw new SQLException("隔离测试管理库连接失败", exception.getSQLState(), exception.getErrorCode());
                }
            }
        };
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(DatasourceRegistrationMapper.class);
        configuration.addMapper(TableBindingMapper.class);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        DatasourceRegistrationMapper resources = session.getMapper(DatasourceRegistrationMapper.class);
        TableBindingMapper tables = session.getMapper(TableBindingMapper.class);
        com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator ids =
                new com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator(31, 31);
        DataOperationCodec codec = new DataOperationCodec(new ObjectMapper());
        DataOperationRepository first = new MybatisDataOperationRepository(resources, tables, ids, codec, jdbc, manager, 10);
        DataOperationRepository second = new MybatisDataOperationRepository(resources, tables, ids, codec, jdbc, manager, 10);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            jdbc.update("INSERT INTO md_datasource_resource(id,datasource_key,datasource_id) VALUES (?,?,1)", ids.nextId(null), key);
            for (String table : payload.getTableNames()) {
                jdbc.update("INSERT INTO md_datasource_table(id,datasource_key,datasource_id,table_name,status) VALUES (?,?,1,?,'CREATED')",
                        ids.nextId(null), key, table);
            }
            CountDownLatch start = new CountDownLatch(1);
            Future<DataOperationRepository.Admission> left = workers.submit(() -> {
                start.await();
                return first.begin(1, IDEMPOTENCY_KEY, payload);
            });
            Future<DataOperationRepository.Admission> right = workers.submit(() -> {
                start.await();
                return second.begin(1, IDEMPOTENCY_KEY, payload);
            });
            start.countDown();
            DataOperationRepository.Admission a = left.get(15, TimeUnit.SECONDS);
            DataOperationRepository.Admission b = right.get(15, TimeUnit.SECONDS);
            assertNotEquals(a.isCreated(), b.isCreated());
            DataOperation operation = a.getOperation();
            assertEquals(operation.getId(), b.getOperation().getId());
            assertEquals(ResourceErrorCode.IDEMPOTENCY_CONFLICT, assertThrows(BizException.class,
                    () -> second.begin(1, IDEMPOTENCY_KEY, parse(key, json(key).replace(SUBJECT, "other-fixture")))).getErrorCode());

            TransactionTemplate ddl = new TransactionTemplate(manager);
            Integer ddlClaim = ddl.execute(status -> {
                resources.lock(key);
                return resources.claim(key, ids.nextId(null).longValue());
            });
            assertEquals(Integer.valueOf(0), ddlClaim);
            first.finish(operation, DataOperation.Result.unknown());
            assertEquals(DataOperation.Status.UNKNOWN, second.byKey(key, IDEMPOTENCY_KEY).get().getResult().getStatus());
            assertEquals(Long.valueOf(operation.getId()), jdbc.queryForObject(
                    "SELECT active_operation_id FROM md_datasource_resource WHERE datasource_key=?", Long.class, key));
        } finally {
            workers.shutdownNow();
            jdbc.update("DELETE FROM md_data_operation WHERE datasource_key=?", key);
            jdbc.update("DELETE FROM md_datasource_table WHERE datasource_key=?", key);
            jdbc.update("DELETE FROM md_datasource_resource WHERE datasource_key=?", key);
        }
    }
}
