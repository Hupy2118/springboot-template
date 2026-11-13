package com.cmbchina.managedb.infrastructure.sql;

import com.cmb.bee.commons.exceptions.BizException;
import com.cmbchina.managedb.domain.AuthorizationSeedPayload;
import com.cmbchina.managedb.domain.DataOperation;
import com.cmbchina.managedb.domain.ResourceErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.*;
import java.util.UUID;

import static com.cmbchina.managedb.AuthorizationSeedFixture.*;
import static org.junit.jupiter.api.Assertions.*;

/** 只在显式提供的隔离 MySQL 测试 Schema 验证真实事务和回读。 */
@EnabledIfEnvironmentVariable(named = "MANAGED_DB_SEED_TEST_JDBC_URL", matches = ".+")
class AuthorizationSeedMysqlTest {
    private final String key = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private final AuthorizationSeedPayload payload = parse(key, json(key));
    private final AuthorizationSeedJdbcAdapter adapter = new AuthorizationSeedJdbcAdapter(id -> connect(), 5);

    /** 在独立测试库创建随机前缀的四表 fixture。 */
    @BeforeEach
    void createTables() throws SQLException {
        create("role", "`role_id` varchar(32) NOT NULL,`role_name` varchar(32) NOT NULL,"
                + "`description` varchar(128),`enable` tinyint NOT NULL,`is_system` tinyint NOT NULL,"
                + "`is_deleted` tinyint NOT NULL,`created_by` varchar(32),UNIQUE (`role_id`)");
        create("resource", "`key` varchar(64) NOT NULL,`name` varchar(32),`description` varchar(128),"
                + "`origin` varchar(8),`type` varchar(9),`target_resource_ref` varchar(255),UNIQUE (`key`)");
        create("role_resource", "`role_id` varchar(32) NOT NULL,`resource_key` varchar(64) NOT NULL,"
                + "`created_by` varchar(32),UNIQUE (`role_id`,`resource_key`)");
        create("role_member", "`role_id` varchar(32) NOT NULL,`member_id` varchar(32) NOT NULL,"
                + "`member_name` varchar(32),`created_by` varchar(32),UNIQUE (`role_id`,`member_id`),UNIQUE (`member_name`)");
    }

    /** 只清理本用例的随机前缀测试表。 */
    @AfterEach
    void dropTables() throws SQLException {
        for (String suffix : new String[]{"role_member", "role_resource", "resource", "role"}) {
            sql("DROP TABLE IF EXISTS " + table(suffix));
        }
    }

    /** 提交后从其他连接读取，重复自然键跳过且业务名称保持原值。 */
    @Test
    void commitsAllRowsAndSkipsExistingWithoutOverwrite() throws SQLException {
        adapter.preflight(1, payload);
        DataOperation.Result first = adapter.execute(1, payload);
        assertEquals(DataOperation.Status.SUCCEEDED, first.getStatus());
        assertEquals(5, first.getCreatedCount());
        assertEquals(2, count("resource"));
        sql("UPDATE " + table("role") + " SET role_name='existing display name'");
        DataOperation.Result second = adapter.execute(1, payload);
        assertEquals(DataOperation.Status.SUCCEEDED, second.getStatus());
        assertEquals(0, second.getCreatedCount());
        assertEquals(5, second.getExistingCount());
        try (Connection connection = connect();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT role_name FROM " + table("role"))) {
            assertTrue(result.next());
            assertEquals("existing display name", result.getString(1));
        }
    }

    /** 第四表额外唯一键拒绝 INSERT 时，前三表写入全部回滚。 */
    @Test
    void rollsBackEarlierTablesWhenFourthInsertFails() throws SQLException {
        sql("INSERT INTO " + table("role_member") + "(role_id,member_id,member_name) "
                + "VALUES ('SYSTEM_ADMIN','other-fixture','Fixture Admin')");
        DataOperation.Result result = adapter.execute(1, payload);
        assertEquals(DataOperation.Status.FAILED, result.getStatus());
        assertEquals(0, count("role"));
        assertEquals(0, count("resource"));
        assertEquals(0, count("role_resource"));
        assertEquals(1, count("role_member"));
    }

    /** 缺表和不匹配的唯一键均在任何写入之前被拒绝。 */
    @Test
    void rejectsMissingAndIncompatibleTables() throws SQLException {
        sql("ALTER TABLE " + table("role_member") + " DROP INDEX role_id");
        assertEquals(ResourceErrorCode.STRUCTURE_MISMATCH,
                assertThrows(BizException.class, () -> adapter.preflight(1, payload)).getErrorCode());
        assertEquals(ResourceErrorCode.STRUCTURE_MISMATCH, adapter.execute(1, payload).getError());
        sql("DROP TABLE " + table("role_member"));
        assertEquals(ResourceErrorCode.STRUCTURE_MISMATCH, adapter.execute(1, payload).getError());
        assertEquals(0, count("role"));
    }

    /** 既有业务资源的引用冲突时回滚当前批次。 */
    @Test
    void rejectsExistingResourceWithConflictingIdentity() throws SQLException {
        sql("INSERT INTO " + table("resource") + "(`key`,origin,type,target_resource_ref) "
                + "VALUES ('home','business','page','page:another')");
        assertEquals(ResourceErrorCode.SEED_DATA_CONFLICT, adapter.execute(1, payload).getError());
        assertEquals(0, count("role"));
        assertEquals(1, count("resource"));
    }

    /** 模拟服务已提交、客户端丢失 COMMIT 响应，明确返回 UNKNOWN。 */
    @Test
    void keepsUnknownWhenCommitResponseIsLost() throws SQLException {
        AuthorizationSeedJdbcAdapter uncertain = new AuthorizationSeedJdbcAdapter(id -> {
            Connection target = connect();
            return (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        try {
                            Object value = method.invoke(target, args);
                            if ("commit".equals(method.getName())) {
                                throw new SQLException("模拟提交响应丢失", "08006");
                            }
                            return value;
                        } catch (java.lang.reflect.InvocationTargetException exception) {
                            throw exception.getCause();
                        }
                    });
        }, 5);
        assertEquals(DataOperation.Status.UNKNOWN, uncertain.execute(1, payload).getStatus());
        assertEquals(1, count("role_member"));
    }

    /** 用显式测试环境变量建立连接，避免在测试异常中打印连接字符串。 */
    private static Connection connect() throws SQLException {
        try {
            return DriverManager.getConnection(System.getenv("MANAGED_DB_SEED_TEST_JDBC_URL"),
                    System.getenv("MANAGED_DB_SEED_TEST_USER"), System.getenv("MANAGED_DB_SEED_TEST_PASSWORD"));
        } catch (SQLException exception) {
            throw new SQLException("隔离测试挂载连接失败", exception.getSQLState(), exception.getErrorCode());
        }
    }

    /** SQL 只用于构造或清理隔离测试 fixture。 */
    private static void sql(String sql) throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    /** 固定测试 DDL 采用 InnoDB、自增主键及数据库生成的时间列。 */
    private void create(String suffix, String fields) throws SQLException {
        sql("CREATE TABLE " + table(suffix) + "(id bigint PRIMARY KEY AUTO_INCREMENT," + fields
                + ",created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");
    }

    /** 拼接测试专用随机前缀及固定后缀。 */
    private String table(String suffix) {
        return "`" + key + "_" + suffix + "`";
    }

    /** 从独立连接验证提交后真实可见的行数。 */
    private int count(String suffix) throws SQLException {
        try (Connection connection = connect();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table(suffix))) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }
}
