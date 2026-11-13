package com.cmbchina.managedb.infrastructure.sql;

import com.cmb.bee.commons.exceptions.BizException;
import com.cmbchina.managedb.domain.ResourceErrorCode;
import com.cmbchina.managedb.domain.TableMetadata;
import com.cmbchina.managedb.infrastructure.datasource.ConfiguredDatabaseSchemas;
import com.cmbchina.managedb.infrastructure.datasource.DatabaseMountProperties;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用 JDBC 替身检查精确绑定参数、实际字段投影和错误语义，不连接真实数据库。
 */
class MySqlTableMetadataTest {
    private MySqlSchemaAccess access;
    private Connection connection;
    private PreparedStatement tableQuery, columnQuery;
    private ResultSet tableRows, columnRows;

    /**
     * 空挂载对象中仅注入假连接池，所有语句和结果均由 Mockito 提供。
     */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        ConfiguredDatabaseSchemas mounts = new ConfiguredDatabaseSchemas(new DatabaseMountProperties(),
                1000, "DISABLED", 1, 1, 2000);
        HikariDataSource pool = mock(HikariDataSource.class);
        connection = mock(Connection.class);
        when(pool.getConnection()).thenReturn(connection);
        when(connection.getCatalog()).thenReturn("shared_schema");
        ((Map<Long, HikariDataSource>)ReflectionTestUtils.getField(mounts, "pools")).put(2L, pool);
        ((Map<Long, String>)ReflectionTestUtils.getField(mounts, "schemas")).put(2L, "shared_schema");
        Statement ruleQuery = mock(Statement.class);
        ResultSet ruleRows = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(ruleQuery);
        when(ruleQuery.executeQuery("SELECT @@lower_case_table_names")).thenReturn(ruleRows);
        when(ruleRows.next()).thenReturn(true);
        when(ruleRows.getInt(1)).thenReturn(0);
        tableQuery = mock(PreparedStatement.class);
        columnQuery = mock(PreparedStatement.class);
        tableRows = mock(ResultSet.class);
        columnRows = mock(ResultSet.class);
        when(connection.prepareStatement(contains("FROM information_schema.TABLES"))).thenReturn(tableQuery);
        when(connection.prepareStatement(contains("FROM information_schema.COLUMNS"))).thenReturn(columnQuery);
        when(tableQuery.executeQuery()).thenReturn(tableRows);
        when(columnQuery.executeQuery()).thenReturn(columnRows);
        when(tableRows.next()).thenReturn(true, false);
        when(tableRows.getString("TABLE_TYPE")).thenReturn("BASE TABLE");
        when(tableRows.getString("TABLE_COMMENT")).thenReturn("订单表");
        when(columnRows.next()).thenReturn(true, true, false);
        when(columnRows.getString("COLUMN_NAME")).thenReturn("id", "is_deleted");
        when(columnRows.getString("COLUMN_TYPE")).thenReturn("bigint", "tinyint(1)");
        when(columnRows.getString("IS_NULLABLE")).thenReturn("NO");
        when(columnRows.getString("COLUMN_KEY")).thenReturn("PRI", "");
        when(columnRows.getString("EXTRA")).thenReturn("auto_increment", "");
        when(columnRows.getString("COLUMN_DEFAULT")).thenReturn(null, "0");
        when(columnRows.getString("COLUMN_COMMENT")).thenReturn("主键", "是否删除");
        access = new MySqlSchemaAccess(mounts, 1, 1);
    }

    /**
     * 精确表名和 Schema 均通过参数绑定，默认值及注释不丢失。
     */
    @Test
    void readsExactTableMetadataAndPreservesDefaults() throws Exception {
        TableMetadata result = access.metadata(2L, "b0ddb23acc91_orders").get();
        assertEquals("订单表", result.getDescription());
        assertEquals(2, result.getColumns().size());
        assertTrue(result.getColumns().get(0).isPrimaryKey());
        assertTrue(result.getColumns().get(0).isAutoIncrement());
        assertNull(result.getColumns().get(0).getDefaultValue());
        assertEquals("0", result.getColumns().get(1).getDefaultValue());
        assertEquals("是否删除", result.getColumns().get(1).getDescription());
        verify(tableQuery).setString(1, "shared_schema");
        verify(tableQuery).setString(2, "b0ddb23acc91_orders");
        verify(columnQuery).setString(1, "shared_schema");
        verify(columnQuery).setString(2, "b0ddb23acc91_orders");
        verify(columnQuery).setQueryTimeout(1);
    }

    /**
     * 表不存在时返回缺失，不执行无归属的全库字段扫描。
     */
    @Test
    void missingTableDoesNotReadColumns() throws Exception {
        when(tableRows.next()).thenReturn(false);
        assertFalse(access.metadata(2L, "missing").isPresent());
        verify(connection, never()).prepareStatement(contains("FROM information_schema.COLUMNS"));
    }

    /**
     * 读取失败保留数据库错误并隐藏驱动中的敏感内容。
     */
    @Test
    void databaseFailureIsNotReportedAsMissing() throws Exception {
        when(columnQuery.executeQuery()).thenThrow(new SQLException("password=not-public"));
        BizException error = assertThrows(BizException.class, () -> access.metadata(2L, "orders"));
        assertEquals(ResourceErrorCode.DATABASE_UNAVAILABLE, error.getErrorCode());
        assertFalse(error.getMessage().contains("not-public"));
    }
}
