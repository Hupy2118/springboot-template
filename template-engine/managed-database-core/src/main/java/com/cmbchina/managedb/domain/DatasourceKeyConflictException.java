package com.cmbchina.managedb.domain;

/**
 * 仅标识已确认且已回滚的 datasource_key 插入冲突，允许重新生成编码。
 */
public final class DatasourceKeyConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * 不携带可能含 SQL 或连接配置的底层异常。
     */
    public DatasourceKeyConflictException() {
        super("数据源标识冲突");
    }
}
