package com.cmbchina.managedb.domain;

import com.cmb.bee.commons.exceptions.BizException;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 启动检查和 SQL 输入的有界业务策略，不包含连接配置。
 */
public final class TableSyncSettings {
    private final int sqlBytes, maxTables, totalBytes;

    /**
     * 由组合根提供当前环境限制。
     */
    public TableSyncSettings(int sqlBytes, int maxTables, int totalBytes) {
        if (sqlBytes < 1 || maxTables < 1 || totalBytes < sqlBytes) {
            throw new IllegalArgumentException("SQL 限制配置无效");
        }
        this.sqlBytes = sqlBytes;
        this.maxTables = maxTables;
        this.totalBytes = totalBytes;
    }

    /**
     * 单条输入按 UTF-8 字节数限制。
     */
    public void validateSql(String sql) {
        if (sql == null || sql.trim().isEmpty() || sql.getBytes(StandardCharsets.UTF_8).length > sqlBytes) {
            throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
        }
    }

    /**
     * 批次另外限制条数和总字节，并沿用单条及检查清单上限。
     */
    public void validateBatch(List<String> sqls) {
        if (sqls == null || sqls.isEmpty() || sqls.size() > 50) {
            throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
        }
        validateList(sqls);
        long bytes = 0;
        for (String sql : sqls) {
            bytes += sql.getBytes(StandardCharsets.UTF_8).length;
        }
        if (bytes > 196608) {
            throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
        }
    }

    /**
     * 先检查全清单大小，避免解析时无限消耗资源。
     */
    public void validateList(List<String> sqls) {
        if (sqls == null || sqls.size() > maxTables) {
            throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
        }
        long bytes = 0;
        for (String sql : sqls) {
            validateSql(sql);
            bytes += sql.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > totalBytes) {
                throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
            }
        }
    }
}
