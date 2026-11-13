package com.cmbchina.managedb.application.view;

import com.cmbchina.managedb.domain.DatasourceResource;
import lombok.AccessLevel;
import lombok.Getter;

/**
 * 不暴露内部主键、凭据或 Schema 的应用只读结果。
 */
@Getter
public final class DatasourceResourceView {
    private final String datasourceKey;
    private final long datasourceId;
    private final String status;
    @Getter(AccessLevel.NONE)
    private final boolean isDeleted;

    /**
     * 从当前领域状态创建不可变投影。
     */
    public DatasourceResourceView(DatasourceResource resource) {
        this.datasourceKey = resource.getDatasourceKey().getValue();
        this.datasourceId = resource.getDatasourceId();
        this.status = resource.getStatus().name();
        this.isDeleted = resource.isDeleted();
    }

    /**
     * 按接口字段名返回软删除标志。
     */
    public boolean getIsDeleted() {
        return isDeleted;
    }
}
