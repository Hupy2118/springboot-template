package com.cmbchina.managedb.infrastructure.sql;

import com.cmb.bee.commons.exceptions.BizException;
import com.cmbchina.managedb.domain.AuthorizationSeedPayload;
import com.cmbchina.managedb.domain.AuthorizationSeedPayload.Table;
import com.cmbchina.managedb.domain.AuthorizationSeedPort;
import com.cmbchina.managedb.domain.DataOperation;
import com.cmbchina.managedb.domain.ResourceErrorCode;
import com.cmbchina.managedb.infrastructure.datasource.ConfiguredDatabaseSchemas;

import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

/** 在固定 MySQL 挂载上执行白名单 INSERT 和回读。 */
public final class AuthorizationSeedJdbcAdapter implements AuthorizationSeedPort {
    private static final Set<String> INTEGER_TYPES = new HashSet<>(
            Arrays.asList("tinyint", "smallint", "mediumint", "int", "bigint"));
    private final Connections connections;
    private final int timeout;

    /** 复用目标挂载连接池，不接受客户端指定位置。 */
    public AuthorizationSeedJdbcAdapter(ConfiguredDatabaseSchemas mounts, int timeout) {
        this(mounts::openConnection, timeout);
    }

    /** 允许测试提供受控挂载连接。 */
    AuthorizationSeedJdbcAdapter(Connections connections, int timeout) {
        this.connections = connections;
        this.timeout = timeout;
    }

    /** 连接工厂仅在 SQL 基础设施内可用。 */
    interface Connections {
        /** 获取固定挂载的一个连接。 */
        Connection open(long datasourceId) throws SQLException;
    }

    /** 受理前只读取物理结构，不写目标库。 */
    @Override
    public void preflight(long datasourceId, AuthorizationSeedPayload payload) {
        try (Connection connection = connections.open(datasourceId)) {
            inspectAll(connection, payload, false);
        } catch (SQLException exception) {
            throw new BizException(ResourceErrorCode.DATABASE_UNAVAILABLE);
        }
    }

    /** 四表事务内完成全部写入和回读；提交异常始终按未知处理。 */
    @Override
    public DataOperation.Result execute(long datasourceId, AuthorizationSeedPayload payload) {
        Connection connection = null;
        boolean transactionStarted = false;
        boolean commitAttempted = false;
        try {
            connection = connections.open(datasourceId);
            connection.setAutoCommit(false);
            transactionStarted = true;
            inspectAll(connection, payload, true);
            int created = 0;
            int total = 0;
            for (Table table : Table.values()) {
                List<Map<String, Object>> rows = payload.getRows().get(table.ordinal());
                created += insertMissing(connection, table, table.physicalName(payload.getDatasourceKey()), rows);
                total += rows.size();
            }
            for (Table table : Table.values()) {
                verifyRows(connection, table, table.physicalName(payload.getDatasourceKey()),
                        payload.getRows().get(table.ordinal()));
            }
            commitAttempted = true;
            connection.commit();
            return DataOperation.Result.committed(created, total - created);
        } catch (SQLException | RuntimeException exception) {
            boolean rolledBack = !transactionStarted || rollback(connection);
            if (commitAttempted || !rolledBack) {
                return DataOperation.Result.unknown();
            }
            ResourceErrorCode code = exception instanceof BizException
                    ? (ResourceErrorCode) ((BizException) exception).getErrorCode()
                    : ResourceErrorCode.SQL_EXECUTION_FAILED;
            return DataOperation.Result.failed(code);
        } finally {
            close(connection);
        }
    }

    /** 事务内先获取四表元数据锁，再检查真实引擎、列和唯一键。 */
    private void inspectAll(Connection connection, AuthorizationSeedPayload payload, boolean lockSchema) throws SQLException {
        String schema = connection.getCatalog();
        if (lockSchema) {
            for (String name : payload.getTableNames()) {
                try (PreparedStatement statement = prepare(connection, "SELECT 1 FROM " + quote(name) + " LIMIT 0");
                     ResultSet ignored = statement.executeQuery()) {
                    // MySQL 将引用的表元数据锁保留到事务结束。
                } catch (SQLException exception) {
                    if ("42S02".equals(exception.getSQLState())) {
                        throw mismatch();
                    }
                    throw exception;
                }
            }
        }
        for (Table table : Table.values()) {
            String name = table.physicalName(payload.getDatasourceKey());
            requireEngine(connection, schema, name);
            requireColumns(connection, schema, name, table);
            requireIndexes(connection, schema, name, table);
            requireNoTriggers(connection, schema, name);
        }
    }

    /** 仅接受精确物理位置上的 InnoDB 基表。 */
    private void requireEngine(Connection connection, String schema, String name) throws SQLException {
        try (PreparedStatement statement = metadata(connection, "SELECT ENGINE,TABLE_TYPE FROM information_schema.TABLES",
                schema, name); ResultSet rows = statement.executeQuery()) {
            if (!rows.next() || !"InnoDB".equalsIgnoreCase(rows.getString("ENGINE"))
                    || !"BASE TABLE".equals(rows.getString("TABLE_TYPE"))) {
                throw mismatch();
            }
        }
    }

    /** 核对字段容量、可写性、自增主键和额外列的默认生成条件。 */
    private void requireColumns(Connection connection, String schema, String name, Table table) throws SQLException {
        Set<String> found = new HashSet<>();
        boolean autoId = false;
        try (PreparedStatement statement = metadata(connection,
                "SELECT COLUMN_NAME,DATA_TYPE,CHARACTER_MAXIMUM_LENGTH,CHARACTER_SET_NAME,IS_NULLABLE,COLUMN_DEFAULT,EXTRA "
                        + "FROM information_schema.COLUMNS", schema, name);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                String column = rows.getString("COLUMN_NAME");
                String type = rows.getString("DATA_TYPE");
                String extra = rows.getString("EXTRA");
                boolean generated = extra.contains("VIRTUAL GENERATED") || extra.contains("STORED GENERATED");
                if ("id".equals(column)) {
                    autoId = INTEGER_TYPES.contains(type) && extra.contains("auto_increment");
                } else if (table.getColumns().containsKey(column)) {
                    int size = table.getColumns().get(column);
                    boolean integer = INTEGER_TYPES.contains(type) || "bit".equals(type);
                    boolean text = ("varchar".equals(type) || "char".equals(type))
                            && rows.getLong("CHARACTER_MAXIMUM_LENGTH") >= size
                            && "utf8mb4".equals(rows.getString("CHARACTER_SET_NAME"));
                    if (generated || (size == 0 ? !integer : !text)
                            || (table.getNaturalKey().contains(column) && !"NO".equals(rows.getString("IS_NULLABLE")))) {
                        throw mismatch();
                    }
                    found.add(column);
                } else if (!generated && "NO".equals(rows.getString("IS_NULLABLE")) && rows.getString("COLUMN_DEFAULT") == null) {
                    throw mismatch();
                }
            }
        }
        if (!autoId || !found.equals(table.getColumns().keySet())) {
            throw mismatch();
        }
    }

    /** 只接受完整主键和覆盖自然键所有列的非前缀唯一索引。 */
    private void requireIndexes(Connection connection, String schema, String name, Table table) throws SQLException {
        Map<String, List<String>> unique = new HashMap<>();
        try (PreparedStatement statement = metadata(connection,
                "SELECT INDEX_NAME,COLUMN_NAME,SUB_PART FROM information_schema.STATISTICS", schema, name,
                " AND NON_UNIQUE=0 ORDER BY INDEX_NAME,SEQ_IN_INDEX");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                unique.computeIfAbsent(rows.getString("INDEX_NAME"), ignored -> new ArrayList<>())
                        .add(rows.getObject("SUB_PART") == null ? rows.getString("COLUMN_NAME") : null);
            }
        }
        Set<String> naturalKey = new HashSet<>(table.getNaturalKey());
        if (!Collections.singletonList("id").equals(unique.get("PRIMARY"))
                || unique.values().stream().noneMatch(columns -> columns.size() == naturalKey.size()
                    && naturalKey.equals(new HashSet<>(columns)))) {
            throw mismatch();
        }
    }

    /** 禁止目标表的触发器产生白名单之外的写入。 */
    private void requireNoTriggers(Connection connection, String schema, String name) throws SQLException {
        try (PreparedStatement statement = prepare(connection, "SELECT 1 FROM information_schema.TRIGGERS "
                + "WHERE BINARY EVENT_OBJECT_SCHEMA=BINARY ? AND BINARY EVENT_OBJECT_TABLE=BINARY ? LIMIT 1")) {
            statement.setString(1, schema);
            statement.setString(2, name);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    throw mismatch();
                }
            }
        }
    }

    /** 使用固定信息架构语句，Schema 和表名精确比较。 */
    private PreparedStatement metadata(Connection connection, String select, String schema, String name) throws SQLException {
        return metadata(connection, select, schema, name, "");
    }

    /** 可选尾部 SQL 只来自适配器内部。 */
    private PreparedStatement metadata(Connection connection, String select, String schema, String name, String suffix) throws SQLException {
        PreparedStatement statement = prepare(connection, select + " WHERE BINARY TABLE_SCHEMA=BINARY ? AND BINARY TABLE_NAME=BINARY ?" + suffix);
        try {
            statement.setString(1, schema);
            statement.setString(2, name);
            return statement;
        } catch (SQLException exception) {
            statement.close();
            throw exception;
        }
    }

    /** 每行先锁定自然键；重复键竞争只能通过精确回读判定为已有。 */
    private int insertMissing(Connection connection, Table table, String name,
                              List<Map<String, Object>> rows) throws SQLException {
        List<String> columns = new ArrayList<>(table.getColumns().keySet());
        String sql = "INSERT INTO " + quote(name) + " (" + quoted(columns) + ") VALUES ("
                + String.join(",", Collections.nCopies(columns.size(), "?")) + ")";
        int created = 0;
        try (PreparedStatement lookup = lookup(connection, table, name);
             PreparedStatement insert = prepare(connection, sql)) {
            for (Map<String, Object> row : rows) {
                if (matches(lookup, table, row)) {
                    continue;
                }
                bind(insert, columns, row);
                try {
                    if (insert.executeUpdate() != 1) {
                        throw new BizException(ResourceErrorCode.SQL_EXECUTION_FAILED);
                    }
                    created++;
                } catch (SQLException exception) {
                    if (exception.getErrorCode() != 1062 || !matches(lookup, table, row)) {
                        throw exception;
                    }
                }
            }
        }
        return created;
    }

    /** 提交前回读所有自然键和固定身份列。 */
    private void verifyRows(Connection connection, Table table, String name,
                            List<Map<String, Object>> rows) throws SQLException {
        try (PreparedStatement lookup = lookup(connection, table, name)) {
            for (Map<String, Object> row : rows) {
                if (!matches(lookup, table, row)) {
                    throw new BizException(ResourceErrorCode.SEED_DATA_CONFLICT);
                }
            }
        }
    }

    /** 锁定使用唯一自然键，不接受客户端提供 SQL 结构。 */
    private PreparedStatement lookup(Connection connection, Table table, String name) throws SQLException {
        String where = table.getNaturalKey().stream().map(column -> quote(column) + "=?")
                .collect(Collectors.joining(" AND "));
        return prepare(connection, "SELECT " + quoted(table.getIdentityColumns()) + " FROM " + quote(name)
                + " WHERE " + where + " FOR UPDATE");
    }

    /** 匹配数据库排序规则找到的行之后，还须精确核对每个身份值。 */
    private static boolean matches(PreparedStatement statement, Table table, Map<String, Object> row) throws SQLException {
        bind(statement, table.getNaturalKey(), row);
        try (ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                return false;
            }
            for (String column : table.getIdentityColumns()) {
                Object expected = row.get(column);
                Object actual = expected instanceof Integer ? result.getInt(column) : result.getString(column);
                if (result.wasNull() || !expected.equals(actual)) {
                    throw new BizException(ResourceErrorCode.SEED_DATA_CONFLICT);
                }
            }
            if (result.next()) {
                throw mismatch();
            }
            return true;
        }
    }

    /** 整数和文本分别绑定，所有请求值只作为参数。 */
    private static void bind(PreparedStatement statement, List<String> columns,
                             Map<String, Object> row) throws SQLException {
        for (int index = 0; index < columns.size(); index++) {
            Object value = row.get(columns.get(index));
            if (value instanceof Integer) {
                statement.setInt(index + 1, (Integer) value);
            } else {
                statement.setString(index + 1, (String) value);
            }
        }
    }

    /** 仅对固定列或由合法前缀导出的表名加引号。 */
    private static String quote(String name) {
        return "`" + name + "`";
    }

    /** 拼接白名单列，保留列顺序。 */
    private static String quoted(List<String> names) {
        return names.stream().map(AuthorizationSeedJdbcAdapter::quote).collect(Collectors.joining(","));
    }

    /** 每个语句沿用挂载 SQL 超时。 */
    private PreparedStatement prepare(Connection connection, String sql) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            statement.setQueryTimeout(timeout);
            return statement;
        } catch (SQLException exception) {
            statement.close();
            throw exception;
        }
    }

    /** 无法确认回滚时不得释放管理占用。 */
    private static boolean rollback(Connection connection) {
        try {
            connection.rollback();
            return true;
        } catch (SQLException | RuntimeException exception) {
            return false;
        }
    }

    /** 释放连接，连接关闭异常不改变已确认的提交或回滚证据。 */
    private static void close(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException | RuntimeException ignored) {
                // 不输出驱动异常，避免泄露 SQL 或连接信息。
            }
        }
    }

    /** 只返回统一结构错误，不公开元数据或异常详情。 */
    private static BizException mismatch() {
        return new BizException(ResourceErrorCode.STRUCTURE_MISMATCH);
    }
}
