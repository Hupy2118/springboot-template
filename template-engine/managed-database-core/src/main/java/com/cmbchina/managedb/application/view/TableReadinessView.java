package com.cmbchina.managedb.application.view;

import com.cmbchina.managedb.domain.StructureDifference;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 当前请求的只读启动门禁，不保存永久同步标志。
 */
@Getter
public final class TableReadinessView {
    private final boolean ready;
    private final long datasourceId;
    private final String reason, operationId, operationStatus;
    private final List<Entry> tables;
    private final List<String> extraTables;

    /**
     * 固定本次检查结果，不把实体模型复制进管理库。
     */
    public TableReadinessView(boolean ready, long datasourceId, String reason, String operationId, String operationStatus, List<Entry> tables,
                              List<String> extraTables) {
        this.ready = ready;
        this.datasourceId = datasourceId;
        this.reason = reason;
        this.operationId = operationId;
        this.operationStatus = operationStatus;
        this.tables = Collections.unmodifiableList(new ArrayList<>(tables));
        this.extraTables = Collections.unmodifiableList(new ArrayList<>(extraTables));
    }

    /**
     * 一张目标表的当前状态和差异。
     */
    @Getter
    public static final class Entry {
        private final String tableName, status;
        private final List<StructureDifference> differences;

        /**
         * 差异来自同一规范化比较器。
         */
        public Entry(String tableName, String status, List<StructureDifference> differences) {
            this.tableName = tableName;
            this.status = status;
            this.differences = Collections.unmodifiableList(new ArrayList<>(differences));
        }
    }
}
