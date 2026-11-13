package com.cmbchina.managedb.domain.repository;

import com.cmbchina.managedb.domain.AuthorizationSeedPayload;
import com.cmbchina.managedb.domain.DataOperation;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Optional;

/** Seed 独立审计与共享资源占用的原子持久化。 */
public interface DataOperationRepository {
    /** 核对当前资源占用及四表归属，返回固定挂载。 */
    long preflight(AuthorizationSeedPayload payload);

    /** 在资源锁内先查幂等记录，再决定是否创建和认领资源。 */
    Admission begin(long datasourceId, String idempotencyKey, AuthorizationSeedPayload payload);

    /** 在工作线程执行 DML 之前重新核对占用和目录。 */
    void requireExecution(DataOperation operation, AuthorizationSeedPayload payload);

    /** 原子保存终态并按结果决定是否释放原占用。 */
    void finish(DataOperation operation, DataOperation.Result result);

    /** 按资源和操作 ID 查询安全摘要。 */
    Optional<DataOperation> detail(String datasourceKey, long id);

    /** 按资源和幂等键查询安全摘要。 */
    Optional<DataOperation> byKey(String datasourceKey, String idempotencyKey);

    /** 只有新受理的调用方可以调度。 */
    @Getter
    @RequiredArgsConstructor
    final class Admission {
        private final DataOperation operation;
        private final boolean created;
    }
}
