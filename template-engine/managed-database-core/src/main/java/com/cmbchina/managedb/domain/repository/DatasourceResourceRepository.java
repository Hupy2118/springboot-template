package com.cmbchina.managedb.domain.repository;

import com.cmbchina.managedb.domain.DatasourceResource;
import com.cmbchina.managedb.domain.valueobject.DatasourceKey;
import com.cmbchina.managedb.domain.valueobject.DatasourceLoad;

import java.util.List;
import java.util.Optional;

/**
 * 数据源资源注册仓储，框架事务和唯一约束异常处理由实现负责。
 */
public interface DatasourceResourceRepository {
    /**
     * 查询候选挂载计数，并补齐查询结果中缺少的零计数候选。
     */
    List<DatasourceLoad> allocationLoads(List<Long> datasourceIds);

    /**
     * 查重包含软删除和已释放记录，不能隐式释放编码。
     */
    boolean existsIncludingDeleted(DatasourceKey datasourceKey);

    /**
     * 在独立短事务中插入新记录，明确的编码冲突在回滚后抛出。
     */
    void insert(DatasourceResource resource);

    /**
     * 按数据源编码恢复原注册状态，包含保留记录。
     */
    Optional<DatasourceResource> findByDatasourceKey(DatasourceKey datasourceKey);
}
