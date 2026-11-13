package com.cmbchina.managedb.infrastructure.sql;

import com.cmb.bee.commons.exceptions.BizException;
import com.cmbchina.managedb.domain.ResourceErrorCode;
import com.cmbchina.managedb.domain.TableDataPage;
import com.cmbchina.managedb.domain.TableDataQuery;
import com.cmbchina.managedb.domain.TableDataQueryPort;
import com.cmbchina.managedb.domain.TableMetadata;
import com.cmbchina.managedb.infrastructure.datasource.ConfiguredDatabaseSchemas;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 固定挂载上的只读单表 SELECT，值投影和返回体均有界，不接受客户端 SQL。
 */
public final class MySqlTableDataQuery implements TableDataQueryPort {
    private static final int CELL_CHARACTERS = 4096, MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private final Connections connections;
    private final int timeoutSeconds;

    /**
     * 查询超时独立配置，不能把预览变成无限运行的后台查询。
     */
    public MySqlTableDataQuery(ConfiguredDatabaseSchemas mounts, int timeoutSeconds) {
        this(mounts::openConnection, timeoutSeconds);
    }

    /**
     * 基础设施连接入口可独立测试，不向应用层公开 JDBC。
     */
    public MySqlTableDataQuery(Connections connections, int timeoutSeconds) {
        if (timeoutSeconds < 1 || timeoutSeconds > 20) {
            throw new IllegalArgumentException("数据查询超时必须为 1 到 20 秒");
        }
        this.connections = connections;
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * 仅在基础设施内部抽象已选固定挂载的连接创建。
     */
    @FunctionalInterface
    public interface Connections {
        /**
         * 返回由本次读取负责关闭的目标连接。
         */
        Connection open(long datasourceId) throws SQLException;
    }

    /**
     * 最多读取页大小加一条，不执行 COUNT，也不把失败当成空表。
     */
    @Override
    public TableDataPage read(long datasourceId, TableMetadata table, TableDataQuery query) {
        query.requireUnfiltered();
        Map<String, String> formats = new LinkedHashMap<>();
        String sql = select(table, formats);
        try (Connection connection = connections.open(datasourceId)) {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setQueryTimeout(timeoutSeconds);
                statement.setMaxRows(query.getPageSize() + 1);
                statement.setFetchSize(query.getPageSize() + 1);
                statement.setInt(1, query.getPageSize() + 1);
                statement.setLong(2, query.offset());
                List<Map<String, String>> rows = new ArrayList<>();
                boolean more = false, truncated = false;
                long budget = 0;
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        if (rows.size() == query.getPageSize()) {
                            more = true;
                            break;
                        }
                        Map<String, String> row = new LinkedHashMap<>();
                        int columnIndex = 1;
                        for (TableMetadata.Column column : table.getColumns()) {
                            String value = result.getString(columnIndex++);
                            if (value != null && value.length() > CELL_CHARACTERS) {
                                int end = CELL_CHARACTERS;
                                if (Character.isHighSurrogate(value.charAt(end - 1)) && Character.isLowSurrogate(value.charAt(end))) {
                                    end--;
                                }
                                value = value.substring(0, end);
                                truncated = true;
                            }
                            // 按最坏 JSON 转义长度估算，避免宽表和大文本撑爆 AG-UI 状态载荷。
                            budget += 6L * column.getName().getBytes(StandardCharsets.UTF_8).length + 16;
                            if (value != null) {
                                budget += 6L * value.getBytes(StandardCharsets.UTF_8).length;
                            }
                            if (budget > MAX_RESPONSE_BYTES) {
                                throw new BizException(ResourceErrorCode.TABLE_DATA_TOO_LARGE);
                            }
                            row.put(column.getName(), value);
                        }
                        rows.add(row);
                    }
                }
                connection.rollback();
                return new TableDataPage(rows, formats, more, truncated);
            }
        } catch (SQLException failure) {
            throw new BizException(ResourceErrorCode.TABLE_DATA_QUERY_FAILED);
        }
    }

    /**
     * 按真实元数据白名单生成有界投影及唯一排序，条件编译以后在本层扩展。
     */
    private static String select(TableMetadata table, Map<String, String> formats) {
        if (table.getColumns().isEmpty() || table.getColumns().size() > 200) {
            throw new BizException(ResourceErrorCode.TABLE_DATA_UNSUPPORTED);
        }
        List<String> columns = new ArrayList<>(), primary = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (TableMetadata.Column column : table.getColumns()) {
            if (!names.add(column.getName().toLowerCase(Locale.ROOT))) {
                throw new BizException(ResourceErrorCode.TABLE_DATA_UNSUPPORTED);
            }
            String name = quote(column.getName());
            boolean binary = binary(column.getType());
            columns.add((binary ? "HEX(SUBSTRING(" + name + ",1,2049))" : "LEFT(CAST(" + name + " AS CHAR),4097)") + " AS " + name);
            formats.put(column.getName(), binary ? "HEX" : "TEXT");
            if (column.isPrimaryKey()) {
                primary.add(name);
            }
        }
        if (primary.isEmpty()) {
            throw new BizException(ResourceErrorCode.TABLE_DATA_UNSUPPORTED);
        }
        return "SELECT " + String.join(",", columns) + " FROM " + quote(table.getTableName()) + " ORDER BY " + String.join(",", primary)
                + " LIMIT ? OFFSET ?";
    }

    /**
     * 二进制及空间字段以十六进制预览，避免数据库字符集转换破坏原始字节。
     */
    private static boolean binary(String type) {
        return type != null && type.toLowerCase(Locale.ROOT).matches(
                "^(tinyblob|blob|mediumblob|longblob|binary|varbinary|bit|geometry|point|linestring|polygon|multipoint|multilinestring|multipolygon|geometrycollection)(\\b|\\().*");
    }

    /**
     * 原样引用已核对标识符并转义反引号，不进行裸字符串 SQL 拼接。
     */
    private static String quote(String name) {
        if (name == null || name.isEmpty() || name.codePointCount(0, name.length()) > 64 || name.indexOf('.') >= 0) {
            throw new BizException(ResourceErrorCode.TABLE_DATA_UNSUPPORTED);
        }
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) {
                throw new BizException(ResourceErrorCode.TABLE_DATA_UNSUPPORTED);
            }
        }
        return "`" + name.replace("`", "``") + "`";
    }
}
