package com.cmbchina.managedb.application.view;

import com.cmbchina.managedb.domain.TableMetadata;
import lombok.Getter;

import java.util.List;

/**
 * 单表字段元数据，只包含当前数据源资源已确认拥有的实际表。
 */
@Getter
public final class TableColumnsView {
    private final String datasourceKey;
    private final String tableName;
    private final String description;
    private final List<TableMetadata.Column> columns;

    /**
     * 引用不可变元数据集合，不返回数据库账号或内部位置。
     */
    public TableColumnsView(String datasourceKey, TableMetadata table) {
        this.datasourceKey = datasourceKey;
        this.tableName = table.getTableName();
        this.description = table.getDescription();
        this.columns = table.getColumns();
    }
}
