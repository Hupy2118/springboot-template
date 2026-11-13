package com.cmbchina.managedb.infrastructure.sql;

import com.cmbchina.managedb.domain.ResourceErrorCode;
import com.cmbchina.managedb.domain.TableDataPage;
import com.cmbchina.managedb.domain.TableDataQuery;
import com.cmbchina.managedb.domain.TableMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 真实 SQL 编译和 JDBC 协议测试全部使用替身，不发送数据库请求。
 */
class MySqlTableDataQueryTest {
    private MySqlTableDataQuery.Connections mounts;
    private Connection connection;
    private PreparedStatement statement;
    private ResultSet result;
    private MySqlTableDataQuery reader;

    /**
     * 配置 JDBC 层返回空页，测试只覆盖调用契约。
     */
    @BeforeEach
    void setUp() throws Exception {
        mounts = mock(MySqlTableDataQuery.Connections.class);
        connection = mock(Connection.class);
        statement = mock(PreparedStatement.class);
        result = mock(ResultSet.class);
        when(mounts.open(2L)).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        reader = new MySqlTableDataQuery(mounts, 5);
    }

    /**
     * 构造有主键的实际元数据，不使用用户提供的 SQL 片段。
     */
    private TableMetadata table(TableMetadata.Column... columns) {
        return new TableMetadata("12ab34cd56ef_orders", "订单", Arrays.asList(columns));
    }

    /**
     * 简化测试列，主键与其他字段的格式完全来自元数据。
     */
    private TableMetadata.Column column(String name, String type, boolean primary) {
        return new TableMetadata.Column(name, type, true, primary, false, null, "");
    }

    /**
     * 默认没有条件的分页意图。
     */
    private TableDataQuery query(int number, int size) {
        return new TableDataQuery(number, size, new TableDataQuery.Filter("AND", Collections.emptyList()));
    }

    /**
     * 参数化分页、唯一排序、只读连接和有界条数必须同时生效。
     */
    @Test
    void bindsPageAndUsesExtraRowWithoutCount() throws Exception {
        when(result.next()).thenReturn(true, true, true);
        when(result.getString(1)).thenReturn("100", "101");
        TableDataPage page = reader.read(2L, table(column("id", "bigint", true)), query(2, 2));
        assertEquals(2, page.getRows().size());
        assertTrue(page.isHasMore());
        assertEquals("100", page.getRows().get(0).get("id"));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertTrue(sql.getValue().startsWith("SELECT "));
        assertTrue(sql.getValue().contains("ORDER BY `id` LIMIT ? OFFSET ?"));
        assertFalse(sql.getValue().contains("COUNT"));
        assertFalse(sql.getValue().contains("SELECT *"));
        verify(statement).setInt(1, 3);
        verify(statement).setLong(2, 2L);
        verify(statement).setQueryTimeout(5);
        verify(statement).setMaxRows(3);
        verify(connection).setReadOnly(true);
        verify(connection).rollback();
        verify(connection).close();
        verify(result, times(2)).getString(1);
    }

    /**
     * 精确文本、NULL 与 HEX 格式都保留，不能让二进制被错误解码。
     */
    @Test
    void preservesNullAndHexFormats() throws Exception {
        when(result.next()).thenReturn(true, false);
        when(result.getString(1)).thenReturn("18446744073709551615");
        when(result.getString(2)).thenReturn(null);
        TableDataPage page = reader.read(2L, table(column("id", "bigint", true), column("attachment", "blob", false)), query(1, 20));
        assertEquals("18446744073709551615", page.getRows().get(0).get("id"));
        assertNull(page.getRows().get(0).get("attachment"));
        assertEquals("HEX", page.getColumnFormats().get("attachment"));
        assertFalse(page.isHasMore());
        verify(connection).prepareStatement(contains("HEX(SUBSTRING(`attachment`,1,2049))"));
    }

    /**
     * 标识符转义保持单一目标，即使名字含反引号也不能拼成其他语句。
     */
    @Test
    void quotesColumnIdentifiersExactly() throws Exception {
        reader.read(2L, table(column("id", "bigint", true), column("odd`name", "varchar(20)", false)), query(1, 20));
        verify(connection).prepareStatement(contains("`odd``name`"));
    }

    /**
     * 没有唯一排序或字段数不受支持时在获取连接前拒绝。
     */
    @Test
    void refusesUnsupportedStructureBeforeConnection() {
        assertSame(ResourceErrorCode.TABLE_DATA_UNSUPPORTED, assertThrows(BizException.class,
                () -> reader.read(2L, table(column("name", "varchar(20)", false)), query(1, 20))).getErrorCode());
        verifyNoInteractions(mounts);
    }

    /**
     * 长文本有显式截断标记，不将完整大对象读入返回体。
     */
    @Test
    void marksTruncatedCell() throws Exception {
        when(result.next()).thenReturn(true, false);
        when(result.getString(1)).thenReturn(String.join("", Collections.nCopies(4097, "x")));
        TableDataPage page = reader.read(2L, table(column("id", "varchar(5000)", true)), query(1, 20));
        assertTrue(page.isTruncated());
        assertEquals(4096, page.getRows().get(0).get("id").length());
    }

    /**
     * 超过响应预算时失败而不是偷偷丢弃行；连接仍然关闭。
     */
    @Test
    void rejectsOversizedPage() throws Exception {
        List<TableMetadata.Column> columns = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            columns.add(column("c" + i, "text", i == 0));
        }
        when(result.next()).thenReturn(true, true, true, true, false);
        when(result.getString(anyInt())).thenReturn(String.join("", Collections.nCopies(4096, "x")));
        assertSame(ResourceErrorCode.TABLE_DATA_TOO_LARGE, assertThrows(BizException.class,
                () -> reader.read(2L, new TableMetadata("orders", "", columns), query(1, 20))).getErrorCode());
        verify(connection).close();
    }

    /**
     * 超时和驱动异常只返回稳定业务错误，不能泄露 JDBC 地址或 SQL。
     */
    @Test
    void hidesDriverFailures() throws Exception {
        when(statement.executeQuery()).thenThrow(new SQLTimeoutException("jdbc:mysql://private/database password=secret"));
        BizException failure = assertThrows(BizException.class, () -> reader.read(2L, table(column("id", "bigint", true)), query(1, 20)));
        assertSame(ResourceErrorCode.TABLE_DATA_QUERY_FAILED, failure.getErrorCode());
        assertFalse(failure.getMessage().contains("secret"));
        verify(connection).close();
    }
}
