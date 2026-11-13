package com.cmbchina.managedb.application.view;

import com.cmbchina.managedb.domain.TableMetadata;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 应用的真实可用表清单，不公开挂载和数据库连接配置。
 */
@Getter
public final class DatasourceTablesView {
    private final String datasourceKey;
    private final List<Entry> tables;

    /**
     * 从本次精确读取结果构造表摘要。
     */
    public DatasourceTablesView(String datasourceKey, List<TableMetadata> metadata) {
        this.datasourceKey = datasourceKey;
        List<Entry> entries = new ArrayList<>();
        for (TableMetadata table : metadata) {
            entries.add(new Entry(table));
        }
        this.tables = Collections.unmodifiableList(entries);
    }

    /**
     * 列表只提供表名、真实注释和当前字段数。
     */
    @Getter
    public static final class Entry {
        private final String tableName;
        private final String description;
        private final int columnCount;

        /**
         * 使用实际字段计算数量，不把设计字段数当真实结构。
         */
        private Entry(TableMetadata table) {
            this.tableName = table.getTableName();
            this.description = table.getDescription();
            this.columnCount = table.getColumns().size();
        }
    }
}
