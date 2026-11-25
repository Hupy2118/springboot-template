package com.cmbchina.managedb.infrastructure.persistence;

import com.cmb.bee.commons.exceptions.BizException;
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
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

import static com.cmbchina.managedb.AuthorizationSeedFixture.*;
import static org.junit.jupiter.api.Assertions.*;

/** 隔离管理库已手工部署四张表时，验证真实资源锁、唯一受理和未知占用。 */
@EnabledIfEnvironmentVariable(named = "MANAGED_DB_SEED_TEST_MANAGEMENT_JDBC_URL", matches = ".+")
class DataOperationMysqlTest {
    /** 并发重试只创建一个新操作，保留旧失败审计和未知占用。 */
    @Test
    void serializesRetryAndRetainsFailedAuditAndUnknownClaim() throws Exception {
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
        factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mybatis/**/*.xml"));
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
            DataOperation original = first.begin(1, IDEMPOTENCY_KEY, payload).getOperation();
            assertEquals(ResourceErrorCode.RESOURCE_BUSY, assertThrows(BizException.class,
                    () -> second.begin(1, RETRY_KEY, payload, original.getId())).getErrorCode());
            first.finish(original, DataOperation.Result.failed(ResourceErrorCode.SEED_DATA_CONFLICT));
            String auditQuery = "SELECT status,result_json,completed_at FROM md_data_operation WHERE id=?";
            Map<String, Object> failedAudit = jdbc.queryForMap(auditQuery, original.getId());
            assertEquals("FAILED", failedAudit.get("status"));
            assertNotNull(failedAudit.get("result_json"));
            assertNotNull(failedAudit.get("completed_at"));

            assertEquals(ResourceErrorCode.RESOURCE_NOT_FOUND, assertThrows(BizException.class,
                    () -> second.begin(1, RETRY_KEY, payload, Long.MAX_VALUE)).getErrorCode());
            AuthorizationSeedPayload otherDatasource = parse(KEY, json(KEY));
            assertEquals(ResourceErrorCode.RESOURCE_NOT_FOUND, assertThrows(BizException.class,
                    () -> second.begin(1, RETRY_KEY, otherDatasource, original.getId())).getErrorCode());
            AuthorizationSeedPayload changed = parse(key, json(key).replace(SUBJECT, "other-fixture"));
            assertEquals(ResourceErrorCode.IDEMPOTENCY_CONFLICT, assertThrows(BizException.class,
                    () -> second.begin(1, RETRY_KEY, changed, original.getId())).getErrorCode());
            assertFalse(second.byKey(key, RETRY_KEY).isPresent());
            assertEquals(original.getId(), second.begin(1, IDEMPOTENCY_KEY, payload, Long.MAX_VALUE).getOperation().getId());

            String completedKey = RETRY_KEY.substring(1) + "c";
            DataOperation completed = first.begin(1, completedKey, payload).getOperation();
            first.finish(completed, DataOperation.Result.committed(5, 0));
            assertEquals(ResourceErrorCode.RESOURCE_BUSY, assertThrows(BizException.class,
                    () -> second.begin(1, RETRY_KEY, payload, completed.getId())).getErrorCode());
            assertFalse(second.byKey(key, RETRY_KEY).isPresent());

            CountDownLatch start = new CountDownLatch(1);
            Future<DataOperationRepository.Admission> left = workers.submit(() -> {
                start.await();
                return first.begin(1, RETRY_KEY, payload, original.getId());
            });
            Future<DataOperationRepository.Admission> right = workers.submit(() -> {
                start.await();
                return second.begin(1, RETRY_KEY, payload, original.getId());
            });
            start.countDown();
            DataOperationRepository.Admission a = left.get(15, TimeUnit.SECONDS);
            DataOperationRepository.Admission b = right.get(15, TimeUnit.SECONDS);
            assertNotEquals(a.isCreated(), b.isCreated());
            DataOperation operation = a.getOperation();
            assertEquals(operation.getId(), b.getOperation().getId());
            assertNotEquals(original.getId(), operation.getId());
            assertEquals(Long.valueOf(operation.getId()), jdbc.queryForObject(
                    "SELECT active_operation_id FROM md_datasource_resource WHERE datasource_key=?", Long.class, key));
            assertEquals(Integer.valueOf(1), jdbc.queryForObject(
                    "SELECT COUNT(*) FROM md_data_operation WHERE datasource_key=? AND idempotency_key=?", Integer.class, key, RETRY_KEY));
            assertEquals(ResourceErrorCode.IDEMPOTENCY_CONFLICT, assertThrows(BizException.class,
                    () -> second.begin(1, RETRY_KEY, changed, Long.MAX_VALUE)).getErrorCode());
            String nextKey = RETRY_KEY.substring(1) + "d";
            assertEquals(ResourceErrorCode.RESOURCE_BUSY, assertThrows(BizException.class,
                    () -> second.begin(1, nextKey, payload, original.getId())).getErrorCode());
            assertFalse(second.byKey(key, nextKey).isPresent());

            TransactionTemplate ddl = new TransactionTemplate(manager);
            Integer ddlClaim = ddl.execute(status -> {
                resources.lock(key);
                return resources.claim(key, ids.nextId(null).longValue());
            });
            assertEquals(Integer.valueOf(0), ddlClaim);
            first.finish(operation, DataOperation.Result.unknown());
            assertEquals(ResourceErrorCode.RESOURCE_BUSY, assertThrows(BizException.class,
                    () -> second.begin(1, nextKey, payload, operation.getId())).getErrorCode());
            assertFalse(second.byKey(key, nextKey).isPresent());
            DataOperationRepository.Admission replay = second.begin(1, RETRY_KEY, payload, Long.MAX_VALUE);
            assertFalse(replay.isCreated());
            assertEquals(operation.getId(), replay.getOperation().getId());
            assertEquals(DataOperation.Status.UNKNOWN, replay.getOperation().getResult().getStatus());
            assertEquals(Long.valueOf(operation.getId()), jdbc.queryForObject(
                    "SELECT active_operation_id FROM md_datasource_resource WHERE datasource_key=?", Long.class, key));
            assertEquals(failedAudit, jdbc.queryForMap(auditQuery, original.getId()));
        } finally {
            workers.shutdownNow();
            jdbc.update("DELETE FROM md_data_operation WHERE datasource_key=?", key);
            jdbc.update("DELETE FROM md_datasource_table WHERE datasource_key=?", key);
            jdbc.update("DELETE FROM md_datasource_resource WHERE datasource_key=?", key);
        }
    }
}
