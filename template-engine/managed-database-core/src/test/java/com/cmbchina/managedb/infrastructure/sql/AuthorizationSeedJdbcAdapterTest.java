package com.cmbchina.managedb.infrastructure.sql;

import com.cmbchina.managedb.domain.AuthorizationSeedPayload;
import com.cmbchina.managedb.domain.DataOperation;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;

import static com.cmbchina.managedb.AuthorizationSeedFixture.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 不依赖数据库即可验证故障分类及事务结束路径。 */
class AuthorizationSeedJdbcAdapterTest {
    private final Connection connection = mock(Connection.class);
    private final AuthorizationSeedJdbcAdapter adapter = new AuthorizationSeedJdbcAdapter(id -> connection, 5);
    private final AuthorizationSeedPayload payload = parse(KEY, json(KEY));

    /** 写入前发生驱动错误，确认回滚后可确定失败。 */
    @Test
    void confirmsRollbackBeforeReportingFailure() throws Exception {
        when(connection.prepareStatement(anyString())).thenThrow(new SQLException("模拟 SQL 失败", "HY000"));
        assertEquals(DataOperation.Status.FAILED, adapter.execute(1, payload).getStatus());
        verify(connection).setAutoCommit(false);
        verify(connection).rollback();
        verify(connection, never()).commit();
        verify(connection).close();
    }

    /** 回滚本身失败时保留 UNKNOWN，禁止重新获取连接或执行。 */
    @Test
    void doesNotTreatUnconfirmedRollbackAsFailure() throws Exception {
        when(connection.prepareStatement(anyString())).thenThrow(new SQLException("模拟 SQL 失败", "HY000"));
        doThrow(new SQLException("模拟回滚响应丢失", "08006")).when(connection).rollback();
        assertEquals(DataOperation.Status.UNKNOWN, adapter.execute(1, payload).getStatus());
        verify(connection).rollback();
        verify(connection, never()).commit();
        verify(connection).close();
    }
}
