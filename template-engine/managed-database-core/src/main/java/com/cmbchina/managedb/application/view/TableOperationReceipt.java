package com.cmbchina.managedb.application.view;

import lombok.Getter;

/**
 * 受理结果不暗示 SQL 已完成，编号按字符串传输。
 */
@Getter
public final class TableOperationReceipt {
    private final String operationId;
    private final String status = "RUNNING";

    /**
     * 仅在 begin 已提交且工作任务已转交后返回。
     */
    public TableOperationReceipt(long id) {
        operationId = Long.toString(id);
    }
}
