package com.cmbchina.managedb.application.view;

import com.cmbchina.managedb.domain.TableDataPage;
import com.cmbchina.managedb.domain.TableDataQuery;
import com.cmbchina.managedb.domain.TableMetadata;
import lombok.Getter;

import java.util.List;
import java.util.Map;

/**
 * 对外只返回当前数据源资源的字段和预览数据，页码及数值文本合同固定。
 */
@Getter
public final class TableDataView {
    private final String status = "SUCCEEDED";
    private final String datasourceKey, tableName;
    private final List<TableMetadata.Column> columns;
    private final List<Map<String, String>> rows;
    private final Map<String, String> columnFormats;
    private final int pageNumber, pageSize;
    private final boolean hasMore, truncated;
    private final boolean filteringSupported = false;

    /**
     * 从不可变领域结果投射，空表也保留真实字段和分页信息。
     */
    public TableDataView(String datasourceKey, TableMetadata table, TableDataQuery query, TableDataPage page) {
        this.datasourceKey = datasourceKey;
        this.tableName = table.getTableName();
        this.columns = table.getColumns();
        this.rows = page.getRows();
        this.columnFormats = page.getColumnFormats();
        this.pageNumber = query.getPageNumber();
        this.pageSize = query.getPageSize();
        this.hasMore = page.isHasMore();
        this.truncated = page.isTruncated();
    }
}
