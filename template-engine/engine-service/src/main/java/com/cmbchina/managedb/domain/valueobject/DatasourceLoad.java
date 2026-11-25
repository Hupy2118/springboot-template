package com.cmbchina.managedb.domain.valueobject;

import com.cmb.bee.commons.exceptions.BizException;
import com.cmbchina.managedb.domain.ResourceErrorCode;
import lombok.Getter;

import java.util.Comparator;
import java.util.List;

/**
 * 某一挂载最近确认的表数和有效资源数，仅用于粗略分配。
 */
@Getter
public final class DatasourceLoad {
    private final long datasourceId;
    private final long createdTableCount;
    private final long registeredResourceCount;

    /**
     * 保存非负统计数据，零表挂载也必须有对应值对象。
     */
    public DatasourceLoad(long datasourceId, long createdTableCount, long registeredResourceCount) {
        if (datasourceId <= 0 || createdTableCount < 0 || registeredResourceCount < 0) {
            throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
        }
        this.datasourceId = datasourceId;
        this.createdTableCount = createdTableCount;
        this.registeredResourceCount = registeredResourceCount;
    }

    /**
     * 按表数、应用数、挂载数值 ID 依次比较，避免依赖配置顺序。
     */
    public static long select(List<DatasourceLoad> candidates) {
        return candidates.stream().min(Comparator.comparingLong(DatasourceLoad::getCreatedTableCount)
                        .thenComparingLong(DatasourceLoad::getRegisteredResourceCount)
                        .thenComparingLong(DatasourceLoad::getDatasourceId))
                .orElseThrow(() -> new BizException(ResourceErrorCode.DATABASE_UNAVAILABLE))
                .getDatasourceId();
    }
}
