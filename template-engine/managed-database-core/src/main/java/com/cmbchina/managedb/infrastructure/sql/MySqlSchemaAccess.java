package com.cmbchina.managedb.infrastructure.sql;

import com.cmb.bee.commons.exceptions.BizException;
import com.cmbchina.managedb.domain.ParsedCreateTable;
import com.cmbchina.managedb.domain.ResourceErrorCode;
import com.cmbchina.managedb.domain.SchemaValues;
import com.cmbchina.managedb.domain.SqlExecutionResult;
import com.cmbchina.managedb.domain.TableMetadata;
import com.cmbchina.managedb.domain.TableStructure;
import com.cmbchina.managedb.infrastructure.datasource.ConfiguredDatabaseSchemas;
import lombok.RequiredArgsConstructor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 精确读取一个目标位置的结构和执行证据，所有 JDBC 对象都在基础设施关闭。
 */
@RequiredArgsConstructor
public final class MySqlSchemaAccess {
    private final ConfiguredDatabaseSchemas mounts;
    private final int sqlTimeout, metadataTimeout;
    private final Map<Long, Integer> caseModes = new ConcurrentHashMap<>();
    private final MySqlCreateTableParser parser = new MySqlCreateTableParser();

    /**
     * 复用无 JDBC 的完整 CREATE 解析。
     */
    public ParsedCreateTable parse(String sql) {
        return parser.parse(sql);
    }

    /**
     * 读取服务器标识规则并缓存；大小写不敏感位置统一落库键。
     */
    public String tableKey(long id, String raw) {
        String name = MySqlCreateTableParser.identifier(raw);
        int mode = caseModes.computeIfAbsent(id, key -> {
            try (Connection c = mounts.openConnection(key); Statement s = c.createStatement()) {
                s.setQueryTimeout(metadataTimeout);
                try (ResultSet r = s.executeQuery("SELECT @@lower_case_table_names")) {
                    if (!r.next()) {
                        throw new SQLException();
                    }
                    return r.getInt(1);
                }
            } catch (SQLException e) {
                throw unavailable();
            }
        });
        if (mode < 0 || mode > 2) {
            throw new BizException(ResourceErrorCode.SQL_UNSUPPORTED);
        }
        return mode == 0 ? name : name.toLowerCase(Locale.ROOT);
    }

    /**
     * 对已规范表名建立精确条件；模式二保留物理大小写但按同一逻辑键查找。
     */
    private String predicate(long id, String column) {
        return caseModes.get(id) == 0 ? "BINARY " + column + " = BINARY ?" : "LOWER(" + column + ") = ?";
    }

    /**
     * 精确查询同一表的各类元数据，连接错误不冒充缺表。
     */
    public Optional<TableStructure> read(long id, String raw) {
        String name = tableKey(id, raw), schema = mounts.schemaName(id);
        try (Connection c = mounts.openConnection(id)) {
            Map<String, String> options = new LinkedHashMap<>();
            try (PreparedStatement s = query(c,
                    "SELECT t.TABLE_TYPE,t.ENGINE,t.TABLE_COLLATION,a.CHARACTER_SET_NAME FROM information_schema.TABLES t LEFT JOIN information_schema.COLLATION_CHARACTER_SET_APPLICABILITY a ON a.COLLATION_NAME=t.TABLE_COLLATION WHERE BINARY t.TABLE_SCHEMA=BINARY ? AND "
                            + predicate(id, "t.TABLE_NAME"), schema, name); ResultSet r = s.executeQuery()) {
                if (!r.next()) {
                    return Optional.empty();
                }
                if (!"BASE TABLE".equals(r.getString(1))) {
                    throw new BizException(ResourceErrorCode.SQL_UNSUPPORTED);
                }
                options.put("ENGINE", r.getString(2).toLowerCase(Locale.ROOT));
                options.put("COLLATE", MySqlCreateTableParser.collation(r.getString(3)));
                options.put("CHARSET", MySqlCreateTableParser.charset(r.getString(4)));
                if (r.next()) {
                    throw new BizException(ResourceErrorCode.TABLE_BINDING_CONFLICT);
                }
            }
            Map<String, TableStructure.Column> columns = new LinkedHashMap<>();
            try (PreparedStatement s = query(c,
                    "SELECT COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COLUMN_DEFAULT,EXTRA,CHARACTER_SET_NAME,COLLATION_NAME FROM information_schema.COLUMNS WHERE BINARY TABLE_SCHEMA=BINARY ? AND "
                            + predicate(id, "TABLE_NAME") + " ORDER BY ORDINAL_POSITION", schema, name); ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    String column = r.getString(1).toLowerCase(Locale.ROOT), type = SchemaValues.type(r.getString(2)), extra =
                            r.getString(5).toLowerCase(Locale.ROOT);
                    if (extra.contains("virtual generated") || extra.contains("stored generated") || extra.contains("invisible")) {
                        throw new BizException(ResourceErrorCode.SQL_UNSUPPORTED);
                    }
                    String def = r.getString(4), value = null, onUpdate = null;
                    if (def != null) {
                        if (type.matches("(timestamp|datetime).*") && def.toUpperCase(Locale.ROOT).startsWith("CURRENT_TIMESTAMP")) {
                            value = SchemaValues.timestamp(def);
                        } else {
                            if (extra.contains("default_generated")) {
                                throw new BizException(ResourceErrorCode.SQL_UNSUPPORTED);
                            }
                            value = SchemaValues.literal(def, type);
                        }
                    }
                    if (extra.contains("on update")) {
                        onUpdate = SchemaValues.timestamp(extra.substring(extra.indexOf("on update") + 9).trim());
                    }
                    columns.put(column,
                            new TableStructure.Column(column, type, "YES".equals(r.getString(3)), value, extra.contains("auto_increment"), onUpdate,
                                    r.getString(6) == null ? null : MySqlCreateTableParser.charset(r.getString(6)),
                                    r.getString(7) == null ? null : MySqlCreateTableParser.collation(r.getString(7))));
                }
            }
            Map<String, List<String>> indexColumns = new LinkedHashMap<>();
            Map<String, String> kinds = new HashMap<>(), methods = new HashMap<>();
            try (PreparedStatement s = query(c,
                    "SELECT INDEX_NAME,NON_UNIQUE,COLUMN_NAME,SUB_PART,COLLATION,INDEX_TYPE FROM information_schema.STATISTICS WHERE BINARY TABLE_SCHEMA=BINARY ? AND "
                            + predicate(id, "TABLE_NAME") + " ORDER BY INDEX_NAME,SEQ_IN_INDEX", schema, name); ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    String key = r.getString(1).toLowerCase(Locale.ROOT), column = r.getString(3);
                    if (column == null) {
                        throw new BizException(ResourceErrorCode.SQL_UNSUPPORTED);
                    }
                    String part = column.toLowerCase(Locale.ROOT);
                    int prefix = r.getInt(4);
                    if (!r.wasNull()) {
                        part += "(" + prefix + ")";
                    }
                    if ("D".equals(r.getString(5))) {
                        part += " DESC";
                    }
                    indexColumns.computeIfAbsent(key, x -> new ArrayList<>()).add(part);
                    kinds.put(key, key.equals("primary") ? "PRIMARY" : r.getInt(2) == 0 ? "UNIQUE" : "INDEX");
                    methods.put(key, r.getString(6).toUpperCase(Locale.ROOT));
                }
            }
            List<TableStructure.Index> indexes = new ArrayList<>();
            for (String key : indexColumns.keySet()) {
                indexes.add(new TableStructure.Index(kinds.get(key), key, indexColumns.get(key), methods.get(key)));
            }
            Map<String, List<String>> locals = new LinkedHashMap<>(), remotes = new HashMap<>();
            Map<String, String> targets = new HashMap<>(), deletes = new HashMap<>(), updates = new HashMap<>();
            String foreignSql =
                    "SELECT k.CONSTRAINT_NAME,k.COLUMN_NAME,k.REFERENCED_TABLE_SCHEMA,k.REFERENCED_TABLE_NAME,k.REFERENCED_COLUMN_NAME,r.DELETE_RULE,r.UPDATE_RULE FROM information_schema.KEY_COLUMN_USAGE k JOIN information_schema.REFERENTIAL_CONSTRAINTS r ON r.CONSTRAINT_SCHEMA=k.CONSTRAINT_SCHEMA AND r.CONSTRAINT_NAME=k.CONSTRAINT_NAME AND r.TABLE_NAME=k.TABLE_NAME WHERE BINARY k.TABLE_SCHEMA=BINARY ? AND "
                            + predicate(id, "k.TABLE_NAME")
                            + " AND k.REFERENCED_TABLE_NAME IS NOT NULL ORDER BY k.CONSTRAINT_NAME,k.ORDINAL_POSITION";
            try (PreparedStatement s = query(c, foreignSql, schema, name); ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    if (!schema.equals(r.getString(3))) {
                        throw new BizException(ResourceErrorCode.SQL_UNSUPPORTED);
                    }
                    String key = r.getString(1).toLowerCase(Locale.ROOT);
                    locals.computeIfAbsent(key, x -> new ArrayList<>()).add(r.getString(2).toLowerCase(Locale.ROOT));
                    remotes.computeIfAbsent(key, x -> new ArrayList<>()).add(r.getString(5).toLowerCase(Locale.ROOT));
                    targets.put(key, tableKey(id, r.getString(4)));
                    deletes.put(key, SchemaValues.action(r.getString(6)));
                    updates.put(key, SchemaValues.action(r.getString(7)));
                }
            }
            List<TableStructure.ForeignKey> foreign = new ArrayList<>();
            for (String key : locals.keySet()) {
                foreign.add(
                        new TableStructure.ForeignKey(key, locals.get(key), targets.get(key), remotes.get(key), deletes.get(key), updates.get(key)));
            }
            if (columns.isEmpty()) {
                throw unavailable();
            }
            return Optional.of(new TableStructure(name, columns, indexes, foreign, options));
        } catch (SQLException e) {
            throw unavailable();
        }
    }

    /**
     * 精确读取表和列注释，默认值保持数据库原值，不扫描共享 Schema。
     */
    public Optional<TableMetadata> metadata(long id, String raw) {
        String name = tableKey(id, raw), schema = mounts.schemaName(id);
        try (Connection c = mounts.openConnection(id)) {
            String description;
            String tableSql = "SELECT TABLE_TYPE,TABLE_COMMENT FROM information_schema.TABLES WHERE BINARY TABLE_SCHEMA=BINARY ? AND " + predicate(id,
                    "TABLE_NAME");
            try (PreparedStatement s = query(c, tableSql, schema, name); ResultSet r = s.executeQuery()) {
                if (!r.next()) {
                    return Optional.empty();
                }
                if (!"BASE TABLE".equals(r.getString("TABLE_TYPE"))) {
                    throw new BizException(ResourceErrorCode.SQL_UNSUPPORTED);
                }
                description = r.getString("TABLE_COMMENT");
                if (r.next()) {
                    throw new BizException(ResourceErrorCode.TABLE_BINDING_CONFLICT);
                }
            }
            List<TableMetadata.Column> columns = new ArrayList<>();
            String columnSql =
                    "SELECT COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COLUMN_DEFAULT,EXTRA,COLUMN_KEY,COLUMN_COMMENT FROM information_schema.COLUMNS WHERE BINARY TABLE_SCHEMA=BINARY ? AND "
                            + predicate(id, "TABLE_NAME") + " ORDER BY ORDINAL_POSITION";
            try (PreparedStatement s = query(c, columnSql, schema, name); ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    String extra = r.getString("EXTRA"), comment = r.getString("COLUMN_COMMENT");
                    columns.add(new TableMetadata.Column(r.getString("COLUMN_NAME"), r.getString("COLUMN_TYPE"),
                            "YES".equals(r.getString("IS_NULLABLE")), "PRI".equals(r.getString("COLUMN_KEY")),
                            extra != null && extra.toLowerCase(Locale.ROOT).contains("auto_increment"),
                            r.getString("COLUMN_DEFAULT"), comment == null ? "" : comment));
                }
            }
            if (columns.isEmpty()) {
                throw unavailable();
            }
            return Optional.of(new TableMetadata(name, description, columns));
        } catch (SQLException e) {
            throw unavailable();
        }
    }

    /**
     * 只判断其他表引用，避免返回其他数据源资源的名称或结构。
     */
    public boolean incoming(long id, String raw) {
        String name = tableKey(id, raw), schema = mounts.schemaName(id);
        String sql = "SELECT TABLE_SCHEMA,TABLE_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE BINARY REFERENCED_TABLE_SCHEMA=BINARY ? AND "
                + predicate(id, "REFERENCED_TABLE_NAME");
        try (Connection c = mounts.openConnection(id); PreparedStatement s = query(c, sql, schema, name); ResultSet r = s.executeQuery()) {
            while (r.next()) {
                if (!schema.equals(r.getString(1)) || !name.equals(tableKey(id, r.getString(2)))) {
                    return true;
                }
            }
            return false;
        } catch (SQLException e) {
            throw unavailable();
        }
    }

    /**
     * 每次 Statement 均有自己的元数据预算和参数绑定。
     */
    private PreparedStatement query(Connection c, String sql, String... values) throws SQLException {
        PreparedStatement s = c.prepareStatement(sql);
        try {
            s.setQueryTimeout(metadataTimeout);
            for (int i = 0; i < values.length; i++) {
                s.setString(i + 1, values[i]);
            }
            return s;
        } catch (SQLException e) {
            s.close();
            throw e;
        }
    }

    /**
     * 执行单条保存内容，SQL 已发送后的不确定异常不自动重试。
     */
    public SqlExecutionResult execute(long id, String sql) {
        if (sql.startsWith("DROP TABLE `")) {
            if (!sql.matches("DROP TABLE `(?:[^`]|``)+`")) {
                throw new BizException(ResourceErrorCode.SQL_UNSUPPORTED);
            }
            MySqlCreateTableParser.identifier(sql.substring(11));
        } else {
            parser.parse(sql);
        }
        boolean sent = false;
        try (Connection c = mounts.openConnection(id); Statement s = c.createStatement()) {
            s.setQueryTimeout(sqlTimeout);
            sent = true;
            s.execute(sql);
            return new SqlExecutionResult(SqlExecutionResult.Outcome.ACKNOWLEDGED, null);
        } catch (SQLException e) {
            if (!sent) {
                return new SqlExecutionResult(SqlExecutionResult.Outcome.NOT_SENT, "DATABASE_UNAVAILABLE");
            }
            String state = e.getSQLState();
            if (state != null && (state.startsWith("42") || state.startsWith("23"))) {
                return new SqlExecutionResult(SqlExecutionResult.Outcome.REJECTED, "SQL_EXECUTION_FAILED");
            }
            return new SqlExecutionResult(SqlExecutionResult.Outcome.UNKNOWN, "EXECUTION_UNKNOWN");
        }
    }

    /**
     * 不泄露 JDBC 错误与配置。
     */
    private static BizException unavailable() {
        return new BizException(ResourceErrorCode.DATABASE_UNAVAILABLE);
    }
}
