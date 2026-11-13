package com.cmbchina.managedb.infrastructure.datasource;

import com.cmb.bee.commons.exceptions.BizException;
import com.cmbchina.managedb.domain.ResourceErrorCode;
import com.cmbchina.managedb.domain.RuntimeDatabaseConnection;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 配置只复用管理位置，不默认返回管理账号或密码。
 */
class ConfiguredRuntimeConnectionsTest {
    /**
     * 构造挂载位置，不创建数据库连接池。
     */
    private DatabaseMountProperties mounts() {
        DatabaseMountProperties.Mount mount = new DatabaseMountProperties.Mount();
        mount.setDatasourceId(2L);
        mount.setHost("localhost");
        mount.setPort(3306);
        mount.setDbName("inner2");
        mount.setUsername("ddl_manager");
        mount.setPassword("test-admin-password");
        DatabaseMountProperties result = new DatabaseMountProperties();
        result.setList(Collections.singletonList(mount));
        return result;
    }

    /**
     * 构造显式专用调试账号配置。
     */
    private RuntimeConnectionProperties runtime(String user, String password, String sslMode) {
        RuntimeConnectionProperties.Entry entry = new RuntimeConnectionProperties.Entry();
        entry.setDatasourceId(2L);
        entry.setUsername(user);
        entry.setPassword(password);
        entry.setSslMode(sslMode);
        RuntimeConnectionProperties result = new RuntimeConnectionProperties();
        result.setList(Collections.singletonList(entry));
        return result;
    }

    /**
     * 位置沿用固定挂载，凭据来自专用调试配置。
     */
    @Test
    void usesMountLocationAndSeparateCredentials() {
        RuntimeDatabaseConnection result =
                new ConfiguredRuntimeConnections(mounts(), runtime("demo", "test-demo-password", "VERIFY_IDENTITY")).resolve(2L);
        assertEquals("inner2", result.getDatabase());
        assertEquals("localhost", result.getHost());
        assertEquals("demo", result.getUsername());
        assertEquals("test-demo-password", result.getPassword());
        assertFalse(result.toString().contains("test-demo-password"));
    }

    /**
     * 缺配置不能回退管理账号。
     */
    @Test
    void missingRuntimeNeverFallsBackToAdmin() {
        ConfiguredRuntimeConnections connections = new ConfiguredRuntimeConnections(mounts(), new RuntimeConnectionProperties());
        BizException error = assertThrows(BizException.class, () -> connections.resolve(2L));
        assertEquals(ResourceErrorCode.RUNTIME_CONNECTION_NOT_CONFIGURED, error.getErrorCode());
    }

    /**
     * 管理账号、root、空密码及任意 JDBC 策略均拒绝。
     */
    @Test
    void rejectsUnsafeOrIncompleteConfiguration() {
        for (String user : new String[]{"ddl_manager", "root", ""}) {
            assertThrows(BizException.class, () -> new ConfiguredRuntimeConnections(mounts(), runtime(user, "test", "DISABLED")).resolve(2L));
        }
        assertThrows(BizException.class, () -> new ConfiguredRuntimeConnections(mounts(), runtime("demo", "", "DISABLED")).resolve(2L));
        assertThrows(BizException.class, () -> new ConfiguredRuntimeConnections(mounts(), runtime("demo", "test", "arbitrary")).resolve(2L));
    }

    /**
     * 重复或未知挂载配置在装配时明确失败。
     */
    @Test
    void rejectsDuplicateAndUnknownMounts() {
        RuntimeConnectionProperties properties = runtime("demo", "test", "DISABLED");
        RuntimeConnectionProperties.Entry entry = properties.getList().get(0);
        properties.setList(java.util.Arrays.asList(entry, entry));
        assertThrows(IllegalArgumentException.class, () -> new ConfiguredRuntimeConnections(mounts(), properties));
        entry.setDatasourceId(99L);
        properties.setList(Collections.singletonList(entry));
        assertThrows(IllegalArgumentException.class, () -> new ConfiguredRuntimeConnections(mounts(), properties));
    }
}
