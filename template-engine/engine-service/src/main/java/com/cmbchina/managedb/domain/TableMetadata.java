package com.cmbchina.managedb.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 真实表的只读元数据，不保存连接、Schema 或执行历史快照。
 */
@Getter
public final class TableMetadata {
    private final String tableName;
    private final String description;
    private final List<Column> columns;

    /**
     * 固定本次读取结果，避免调用方修改字段集合。
     */
    public TableMetadata(String tableName, String description, List<Column> columns) {
        this.tableName = tableName;
        this.description = description == null ? "" : description;
        this.columns = Collections.unmodifiableList(new ArrayList<>(columns));
    }

    /**
     * 字段保留实际类型、默认值及注释，不套用结构比较器的内部标记。
     */
    @Getter
    @RequiredArgsConstructor
    public static final class Column {
        private final String name;
        private final String type;
        private final boolean nullable;
        private final boolean primaryKey;
        private final boolean autoIncrement;
        private final String defaultValue;
        private final String description;
    }
}
