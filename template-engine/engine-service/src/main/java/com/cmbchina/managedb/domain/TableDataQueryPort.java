package com.cmbchina.managedb.domain;

/**
 * 专用只读查询端口，后续条件能力保持相同意图及分页结果边界。
 */
public interface TableDataQueryPort {
    /**
     * 依据已核对的真实表字段读取一页，不接受任意 SQL 或 Schema。
     */
    TableDataPage read(long datasourceId, TableMetadata table, TableDataQuery query);
}
