package com.cmbchina.managedb.domain.repository;

import com.cmbchina.managedb.domain.Actor;
import com.cmbchina.managedb.domain.CheckContext;
import com.cmbchina.managedb.domain.TableBinding;
import com.cmbchina.managedb.domain.TableOperation;

import java.util.List;
import java.util.Optional;

/**
 * 统一执行管理的受控事务，不将 Mapper 或事务框架交给应用层。
 */
public interface TableOperationRepository {
    /**
     * 生成统一内部操作 ID。
     */
    long nextId();

    /**
     * 持久化放弃请求，与步骤准入使用同一资源锁；不释放未知占用。
     */
    TableOperation requestAbandon(String datasourceKey, long operationId, String reason, Actor actor);

    /**
     * 读取应用及全部保留操作数。
     */
    CheckContext checkContext(String datasourceKey);

    /**
     * 查位置归属，包含软删除。
     */
    Optional<TableBinding> binding(long datasourceId, String tableName);

    /**
     * 返回应用有效目录，供额外旧表展示。
     */
    List<TableBinding> bindings(String datasourceKey);

    /**
     * 清理读取全部保留归属，软删目录也不能被遗漏后宣告整份数据源资源已回收。
     */
    List<TableBinding> cleanupBindings(String datasourceKey);

    /**
     * 原子登记目录、创建操作并认领占用，核对预检查时的目录状态。
     */
    void begin(TableOperation operation, TableBinding.Status expectedStatus);

    /**
     * 批次在同一资源锁内复核预检计数和目录后原子认领。
     */
    void beginBatch(TableOperation operation, List<TableBinding.Status> expectedStatuses, long expectedOperationCount);

    /**
     * 在资源锁内复核操作计数及完整清单，清理不创建或认领目录外表。
     */
    void beginCleanup(TableOperation operation, long expectedOperationCount);

    /**
     * 保存步骤及可选目录变化，核对当前占用。
     */
    void saveStep(TableOperation operation, TableBinding.Status tableStatus);

    /**
     * 保存终态及确定结果的占用释放，UNKNOWN 保留占用。
     */
    void finish(TableOperation operation);

    /**
     * 按资源与操作 ID读取历史，不触发重放。
     */
    Optional<TableOperation> detail(String datasourceKey, long operationId);

    /**
     * 最近历史不隐式隐藏执行记录。
     */
    List<TableOperation> recent(String datasourceKey, int limit);
}
