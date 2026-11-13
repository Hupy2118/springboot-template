package com.cmbchina.managedb.domain;

import com.cmb.bee.commons.exceptions.BizException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * 内存中的表结构，期望与实际共用；集合不可变且不携带 JDBC 或解析器对象。
 */
@Getter
public final class TableStructure {
    private final String tableName;
    private final Map<String, Column> columns;
    private final List<Index> indexes;
    private final List<ForeignKey> foreignKeys;
    private final Map<String, String> options;

    /**
     * 固定一份结构，不保存数据库快照。
     */
    public TableStructure(String tableName, Map<String, Column> columns, List<Index> indexes,
                          List<ForeignKey> foreignKeys, Map<String, String> options) {
        this.tableName = tableName;
        this.columns = Collections.unmodifiableMap(new LinkedHashMap<>(columns));
        this.indexes = Collections.unmodifiableList(new ArrayList<>(indexes));
        this.foreignKeys = Collections.unmodifiableList(new ArrayList<>(foreignKeys));
        this.options = Collections.unmodifiableMap(new LinkedHashMap<>(options));
    }

    /**
     * 用户期望结构必须显式定义主键；唯一索引和数据库隐藏索引不能代替主键。
     */
    public void requireExplicitPrimaryKey() {
        if (indexes.stream().noneMatch(index -> "PRIMARY".equals(index.getKind()) && !index.getColumns().isEmpty())) {
            throw new BizException(ResourceErrorCode.PRIMARY_KEY_REQUIRED);
        }
    }

    /**
     * 按目标数据库规则统一表位置键，原 SQL 不受影响。
     */
    public TableStructure canonicalTables(UnaryOperator<String> key) {
        List<ForeignKey> refs = new ArrayList<>();
        for (ForeignKey f : foreignKeys) {
            refs.add(new ForeignKey(f.name, f.columns, key.apply(f.targetTable), f.targetColumns, f.onDelete, f.onUpdate));
        }
        return new TableStructure(key.apply(tableName), columns, indexes, refs, options);
    }

    /**
     * 列的运行语义，默认值和类型已规范化。
     */
    @Getter
    @RequiredArgsConstructor
    public static final class Column {
        private final String name;
        private final String type;
        private final boolean nullable;
        private final String defaultValue;
        private final boolean autoIncrement;
        private final String onUpdate;
        private final String charset;
        private final String collation;

        /**
         * 主键隐式不可空，不改写用户原文。
         */
        public Column required() {
            return new Column(name, type, false, defaultValue, autoIncrement, onUpdate, charset, collation);
        }

        /**
         * 将明确的字符选项继承到字符列，列自己的声明优先。
         */
        public Column characterDefaults(String tableCharset, String tableCollation) {
            if (!type.matches("(char|varchar|tinytext|text|mediumtext|longtext).*")) {
                return this;
            }
            String inheritedCharset = charset != null ? charset : collation != null ? collation.split("_", 2)[0] :
                    tableCharset != null ? tableCharset : tableCollation != null ? tableCollation.split("_", 2)[0] : null;
            String inheritedCollation = collation != null ? collation : charset != null ? null : tableCollation;
            return new Column(name, type, nullable, defaultValue, autoIncrement, onUpdate, inheritedCharset, inheritedCollation);
        }

        /**
         * 返回默认值语义。
         */
        public String getDefaultValue() {
            return defaultValue == null ? (nullable ? "NULL" : "NONE") : defaultValue;
        }
    }

    /**
     * 索引的列顺序及前缀长度保留在 columns 中。
     */
    @Getter
    public static final class Index {
        private final String kind, name, using;
        private final List<String> columns;

        /**
         * 固定索引语义，未显式命名时 name 为空。
         */
        public Index(String kind, String name, List<String> columns, String using) {
            this.kind = kind;
            this.name = name;
            this.columns = Collections.unmodifiableList(new ArrayList<>(columns));
            this.using = using;
        }

        /**
         * 返回不依赖自动名称的比较签名。
         */
        public String signature() {
            return kind + ":" + columns + ":" + using;
        }
    }

    /**
     * 外键定义，名称与结构分开比较。
     */
    @Getter
    public static final class ForeignKey {
        private final String name, targetTable, onDelete, onUpdate;
        private final List<String> columns, targetColumns;

        /**
         * 固定外键两端及动作。
         */
        public ForeignKey(String name, List<String> columns, String targetTable, List<String> targetColumns, String onDelete, String onUpdate) {
            this.name = name;
            this.columns = Collections.unmodifiableList(new ArrayList<>(columns));
            this.targetTable = targetTable;
            this.targetColumns = Collections.unmodifiableList(new ArrayList<>(targetColumns));
            this.onDelete = onDelete;
            this.onUpdate = onUpdate;
        }

        /**
         * 返回结构签名。
         */
        public String signature() {
            return columns + ":" + targetTable + ":" + targetColumns + ":" + onDelete + ":" + onUpdate;
        }
    }
}
