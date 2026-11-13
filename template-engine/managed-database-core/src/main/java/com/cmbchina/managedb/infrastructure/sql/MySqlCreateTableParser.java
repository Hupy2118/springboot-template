package com.cmbchina.managedb.infrastructure.sql;

import com.cmb.bee.commons.exceptions.BizException;
import com.cmbchina.managedb.domain.ParsedCreateTable;
import com.cmbchina.managedb.domain.ResourceErrorCode;
import com.cmbchina.managedb.domain.SchemaValues;
import com.cmbchina.managedb.domain.TableStructure;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.create.table.ColDataType;
import net.sf.jsqlparser.statement.create.table.ColumnDefinition;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.create.table.ForeignKeyIndex;
import net.sf.jsqlparser.statement.create.table.Index;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 通过 AST 解析完整 CREATE；未知选项一律拒绝，用户原文从不重写。
 */
public final class MySqlCreateTableParser {
    /**
     * 只接受单条基础 CREATE，防止前缀检查遗漏后续语句。
     */
    public ParsedCreateTable parse(String sql) {
        try {
            if (sql == null || sql.trim().isEmpty() || sql.contains("/*!") || sql.contains("/*M!") || sql.indexOf('\\') >= 0) {
                throw unsupported();
            }
            List<Statement> statements = CCJSqlParserUtil.parseStatements(sql).getStatements();
            if (statements.size() != 1 || !(statements.get(0) instanceof CreateTable)) {
                throw unsupported();
            }
            CreateTable create = (CreateTable)statements.get(0);
            if (create.isIfNotExists() || create.isOrReplace() || create.isUnlogged() || create.getSelect() != null || create.getLikeTable() != null
                    || create.getRowMovement() != null
                    || create.getCreateOptionsStrings() != null && !create.getCreateOptionsStrings().isEmpty()
                    || create.getColumnDefinitions() == null || create.getColumnDefinitions().isEmpty()) {
                throw unsupported();
            }
            String name = tableName(create.getTable());
            if (name.toLowerCase(Locale.ROOT).startsWith("md_")) {
                throw unsupported();
            }
            Map<String, TableStructure.Column> columns = new LinkedHashMap<>();
            List<TableStructure.Index> indexes = new ArrayList<>();
            List<TableStructure.ForeignKey> foreign = new ArrayList<>();
            for (ColumnDefinition column : create.getColumnDefinitions()) {
                TableStructure.Column parsed = column(column, indexes);
                if (columns.put(parsed.getName(), parsed) != null) {
                    throw unsupported();
                }
            }
            if (create.getIndexes() != null) {
                for (Index index : create.getIndexes()) {
                    if (index instanceof ForeignKeyIndex) {
                        ForeignKeyIndex f = (ForeignKeyIndex)index;
                        List<String> local = names(f.getColumnsNames()), remote = names(f.getReferencedColumnNames());
                        if (local.size() != remote.size()) {
                            throw unsupported();
                        }
                        foreign.add(new TableStructure.ForeignKey(optionalIdentifier(f.getName()), local, tableName(f.getTable()), remote,
                                SchemaValues.action(f.getOnDeleteReferenceOption()), SchemaValues.action(f.getOnUpdateReferenceOption())));
                    } else {
                        String kind =
                                index.getType() == null ? "INDEX" :
                                        index.getType().toUpperCase(Locale.ROOT).replace(" KEY", "").replace(" INDEX", "");
                        if (kind.equals("KEY")) {
                            kind = "INDEX";
                        }
                        if (kind.equals("PRIMARY")) {
                            kind = "PRIMARY";
                        }
                        if (!Arrays.asList("PRIMARY", "UNIQUE", "INDEX").contains(kind) || index.getIndexSpec() != null && !index.getIndexSpec()
                                .isEmpty()) {
                            throw unsupported();
                        }
                        String method = index.getUsing() == null ? "BTREE" : index.getUsing().toUpperCase(Locale.ROOT);
                        if (!Arrays.asList("BTREE", "HASH").contains(method)) {
                            throw unsupported();
                        }
                        indexes.add(new TableStructure.Index(kind, kind.equals("PRIMARY") ? "primary" : optionalIdentifier(index.getName()),
                                indexColumns(index), method));
                    }
                }
            }
            Set<String> indexNames = new HashSet<>();
            int primary = 0;
            for (TableStructure.Index index : indexes) {
                if (index.getName() != null && !indexNames.add(index.getName())) {
                    throw unsupported();
                }
                if (index.getKind().equals("PRIMARY") && ++primary > 1) {
                    throw unsupported();
                }
                for (String part : index.getColumns()) {
                    String key = part.split("[ (]", 2)[0];
                    if (!columns.containsKey(key)) {
                        throw unsupported();
                    }
                    if (index.getKind().equals("PRIMARY")) {
                        columns.put(key, columns.get(key).required());
                    }
                }
            }
            for (TableStructure.ForeignKey f : foreign) {
                for (String key : f.getColumns()) {
                    if (!columns.containsKey(key)) {
                        throw unsupported();
                    }
                }
            }
            Map<String, String> tableOptions = options(create.getTableOptionsStrings());
            Map<String, TableStructure.Column> completed = new LinkedHashMap<>();
            for (TableStructure.Column column : columns.values()) {
                completed.put(column.getName(), column.characterDefaults(tableOptions.get("CHARSET"), tableOptions.get("COLLATE")));
            }
            return new ParsedCreateTable(sql, new TableStructure(name, completed, indexes, foreign, tableOptions));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw unsupported();
        }
    }

    /**
     * 严格解析 AST 列选项，不忽略生成列或表达式约束。
     */
    private TableStructure.Column column(ColumnDefinition c, List<TableStructure.Index> indexes) throws Exception {
        String name = identifier(c.getColumnName()).toLowerCase(Locale.ROOT);
        ColDataType dt = c.getColDataType();
        if (dt.getArrayData() != null && !dt.getArrayData().isEmpty()) {
            throw unsupported();
        }
        String raw = dt.getDataType();
        if (dt.getArgumentsStringList() != null && !dt.getArgumentsStringList().isEmpty()) {
            raw += "(" + String.join(",", dt.getArgumentsStringList()) + ")";
        }
        List<String> specs = columnSpecs(c.getColumnSpecs());
        boolean nullable = true, auto = false, unsigned = false;
        String defaultText = null, onUpdate = null, charset = dt.getCharacterSet() == null ? null : charset(dt.getCharacterSet()), collation = null;
        for (int i = 0; i < specs.size(); i++) {
            String token = specs.get(i).toUpperCase(Locale.ROOT);
            switch (token) {
                case "NOT":
                    if (++i >= specs.size() || !specs.get(i).equalsIgnoreCase("NULL")) {
                        throw unsupported();
                    }
                    nullable = false;
                    break;
                case "NULL":
                    nullable = true;
                    break;
                case "UNSIGNED":
                    unsigned = true;
                    break;
                case "AUTO_INCREMENT":
                    auto = true;
                    break;
                case "DEFAULT":
                    if (++i >= specs.size()) {
                        throw unsupported();
                    }
                    defaultText = specs.get(i);
                    if ((defaultText.equals("-") || defaultText.equals("+")) && i + 1 < specs.size()) {
                        defaultText += specs.get(++i);
                    }
                    if (defaultText.equalsIgnoreCase("CURRENT_TIMESTAMP") && i + 1 < specs.size() && specs.get(i + 1).startsWith("(")) {
                        defaultText += specs.get(++i);
                    }
                    break;
                case "ON":
                    if (++i >= specs.size() || !specs.get(i).equalsIgnoreCase("UPDATE") || ++i >= specs.size()) {
                        throw unsupported();
                    }
                    onUpdate = specs.get(i);
                    if (onUpdate.equalsIgnoreCase("CURRENT_TIMESTAMP") && i + 1 < specs.size() && specs.get(i + 1).startsWith("(")) {
                        onUpdate += specs.get(++i);
                    }
                    break;
                case "PRIMARY":
                    if (++i >= specs.size() || !specs.get(i).equalsIgnoreCase("KEY")) {
                        throw unsupported();
                    }
                    nullable = false;
                    indexes.add(new TableStructure.Index("PRIMARY", "primary", Collections.singletonList(name), "BTREE"));
                    break;
                case "UNIQUE":
                    if (i + 1 < specs.size() && specs.get(i + 1).equalsIgnoreCase("KEY")) {
                        i++;
                    }
                    indexes.add(new TableStructure.Index("UNIQUE", null, Collections.singletonList(name), "BTREE"));
                    break;
                case "COMMENT":
                    if (++i >= specs.size() || !(CCJSqlParserUtil.parseExpression(specs.get(i)) instanceof StringValue)) {
                        throw unsupported();
                    }
                    break;
                case "COLLATE":
                    if (++i >= specs.size()) {
                        throw unsupported();
                    }
                    collation = collation(specs.get(i));
                    break;
                case "CHARACTER":
                    if (++i >= specs.size() || !specs.get(i).equalsIgnoreCase("SET") || ++i >= specs.size()) {
                        throw unsupported();
                    }
                    charset = charset(specs.get(i));
                    break;
                default:
                    throw unsupported();
            }
        }
        String type = SchemaValues.type(raw + (unsigned ? " unsigned" : ""));
        String value = null;
        if (defaultText != null) {
            Expression expression = CCJSqlParserUtil.parseExpression(defaultText);
            if (expression instanceof NullValue) {
                value = "NULL";
            } else if (expression instanceof StringValue) {
                StringValue s = (StringValue)expression;
                if (s.getPrefix() != null && !s.getPrefix().isEmpty()) {
                    throw unsupported();
                }
                value = SchemaValues.literal(s.getNotExcapedValue(), type);
            } else if (defaultText.matches("[-+]?[0-9]+(\\.[0-9]+)?")) {
                value = SchemaValues.literal(defaultText, type);
            } else {
                value = SchemaValues.timestamp(defaultText);
            }
        }
        return new TableStructure.Column(name, type, nullable, value, auto, onUpdate == null ? null : SchemaValues.timestamp(onUpdate), charset,
                collation);
    }

    /**
     * 合并时间函数被 AST 分开的括号令牌，其他表达式仍交给白名单拒绝。
     */
    private static List<String> columnSpecs(List<String> source) {
        if (source == null) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (int i = 0; i < source.size(); i++) {
            String token = source.get(i);
            if (token.equalsIgnoreCase("CURRENT_TIMESTAMP") && i + 1 < source.size() && source.get(i + 1).startsWith("(")) {
                StringBuilder function = new StringBuilder(token);
                do {
                    function.append(source.get(++i));
                } while (!source.get(i).contains(")") && i + 1 < source.size());
                token = function.toString();
            }
            result.add(token);
        }
        return result;
    }

    /**
     * 表选项从 AST 令牌读取，只保留明确影响结构的选项。
     */
    private Map<String, String> options(List<String> options) throws Exception {
        List<String> tokens = new ArrayList<>();
        if (options != null) {
            for (String token : options) {
                if (token.startsWith("'")) {
                    tokens.add(token);
                } else {
                    for (String part : token.split("\\s+|(?==)|(?<==)")) {
                        if (!part.isEmpty()) {
                            tokens.add(part);
                        }
                    }
                }
            }
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < tokens.size(); i++) {
            String key = tokens.get(i).toUpperCase(Locale.ROOT);
            if (key.equals("DEFAULT")) {
                if (++i >= tokens.size()) {
                    throw unsupported();
                }
                key = tokens.get(i).toUpperCase(Locale.ROOT);
            }
            if (key.equals("CHARACTER")) {
                if (++i >= tokens.size() || !tokens.get(i).equalsIgnoreCase("SET")) {
                    throw unsupported();
                }
                key = "CHARSET";
            }
            if (!Arrays.asList("ENGINE", "CHARSET", "COLLATE", "COMMENT").contains(key)) {
                throw unsupported();
            }
            if (++i < tokens.size() && tokens.get(i).equals("=")) {
                i++;
            }
            if (i >= tokens.size()) {
                throw unsupported();
            }
            String value = tokens.get(i);
            if (key.equals("COMMENT")) {
                if (!(CCJSqlParserUtil.parseExpression(value) instanceof StringValue)) {
                    throw unsupported();
                }
                continue;
            }
            if (key.equals("CHARSET")) {
                value = charset(value);
            } else if (key.equals("COLLATE")) {
                value = collation(value);
            } else {
                value = identifier(value).toLowerCase(Locale.ROOT);
            }
            if (result.put(key, value) != null) {
                throw unsupported();
            }
        }
        return result;
    }

    /**
     * 索引保留前缀长度和排序方向，不接受表达式索引。
     */
    private List<String> indexColumns(Index index) {
        List<String> result = new ArrayList<>();
        for (Index.ColumnParams column : index.getColumnWithParams()) {
            String part = identifier(column.getColumnName()).toLowerCase(Locale.ROOT);
            String params = column.getParams() == null ? "" : String.join("", column.getParams()).replace(" ", "").toUpperCase(Locale.ROOT);
            if (!params.matches("(\\([0-9]+\\))?(ASC|DESC)?")) {
                throw unsupported();
            }
            if (params.startsWith("(")) {
                int close = params.indexOf(')');
                part += params.substring(0, close + 1);
                params = params.substring(close + 1);
            }
            if (params.equals("DESC")) {
                part += " DESC";
            }
            result.add(part);
        }
        if (result.isEmpty()) {
            throw unsupported();
        }
        return result;
    }

    /**
     * 只接受本 Schema 的单一表名。
     */
    private static String tableName(Table table) {
        if (table.getSchemaName() != null || table.getDatabase() != null && table.getDatabase().getDatabaseName() != null) {
            throw unsupported();
        }
        return identifier(table.getName());
    }

    /**
     * 解开合法反引号；禁止位置限定及控制字符。
     */
    public static String identifier(String raw) {
        if (raw == null) {
            throw unsupported();
        }
        String name = raw;
        if (name.startsWith("`") && name.endsWith("`")) {
            name = name.substring(1, name.length() - 1).replace("``", "`");
        }
        if (name.isEmpty() || name.codePointCount(0, name.length()) > 64 || name.indexOf('.') >= 0 || name.indexOf('"') >= 0 || !name.equals(
                name.trim())) {
            throw unsupported();
        }
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) {
                throw unsupported();
            }
        }
        return name;
    }

    /**
     * 规范列及约束标识，列名和索引名按 MySQL 非大小写语义比较。
     */
    private static String optionalIdentifier(String raw) {
        return raw == null ? null : identifier(raw).toLowerCase(Locale.ROOT);
    }

    /**
     * 保留有序列清单。
     */
    private static List<String> names(List<String> source) {
        if (source == null || source.isEmpty()) {
            throw unsupported();
        }
        List<String> result = new ArrayList<>();
        for (String s : source) {
            result.add(identifier(s).toLowerCase(Locale.ROOT));
        }
        return result;
    }

    /**
     * MySQL 的 utf8 别名归一，其他配置不改写。
     */
    public static String charset(String raw) {
        String value = identifier(raw).toLowerCase(Locale.ROOT);
        return value.equals("utf8") ? "utf8mb3" : value;
    }

    /**
     * 排序规则的 utf8 别名归一。
     */
    public static String collation(String raw) {
        return identifier(raw).toLowerCase(Locale.ROOT).replaceFirst("^utf8_", "utf8mb3_");
    }

    /**
     * 只抛出固定错误，不输出解析器异常中的用户原文。
     */
    private static BizException unsupported() {
        return new BizException(ResourceErrorCode.SQL_UNSUPPORTED);
    }
}
