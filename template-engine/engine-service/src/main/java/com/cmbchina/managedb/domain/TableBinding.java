package com.cmbchina.managedb.domain;

import com.cmb.bee.commons.exceptions.BizException;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 表位置归属与最近确认的物理状态，不代表当前实体同步。
 */
@Getter
@AllArgsConstructor
public final class TableBinding {
    public enum Status {REGISTERED, CREATED, DROPPED}

    private final String datasourceKey;
    private final long datasourceId;
    private final String tableName;
    private Status status;
    private final boolean deleted;

    /**
     * 核对精确位置及有效归属。
     */
    public void requireOwner(String datasourceKey, long datasourceId) {
        if (deleted || !this.datasourceKey.equals(datasourceKey) || this.datasourceId != datasourceId) {
            throw new BizException(ResourceErrorCode.TABLE_BINDING_CONFLICT);
        }
    }

    /**
     * 明确回读建成后更新状态。
     */
    public void created() {
        if (deleted) {
            throw new BizException(ResourceErrorCode.TABLE_BINDING_CONFLICT);
        }
        status = Status.CREATED;
    }

    /**
     * 明确回读删除后更新状态。
     */
    public void dropped() {
        if (deleted) {
            throw new BizException(ResourceErrorCode.TABLE_BINDING_CONFLICT);
        }
        status = Status.DROPPED;
    }
}
