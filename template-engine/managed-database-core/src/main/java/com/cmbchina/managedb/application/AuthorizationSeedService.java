package com.cmbchina.managedb.application;

import com.cmb.bee.commons.exceptions.BizException;
import com.cmbchina.managedb.application.view.DataOperationView;
import com.cmbchina.managedb.domain.*;
import com.cmbchina.managedb.domain.repository.DataOperationRepository;
import com.cmbchina.managedb.domain.valueobject.DatasourceKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/** 编排权限 Seed 的幂等受理、唯一调度和安全查询。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthorizationSeedService {
    private final DataOperationRepository repository;
    private final AuthorizationSeedPort database;
    private final TableTaskDispatcher dispatcher;

    /** 先查原记录，新操作预检通过且管理事务提交后才调度。 */
    public DataOperationView.Receipt submit(String rawIdempotencyKey, AuthorizationSeedPayload payload) {
        String idempotencyKey = AuthorizationSeedPayload.requireIdempotencyKey(rawIdempotencyKey);
        String key = payload.getDatasourceKey();
        Optional<DataOperation> previous = repository.byKey(key, idempotencyKey);
        if (previous.isPresent()) {
            return receipt(previous.get().requireFingerprint(payload.getFingerprint()));
        }
        try {
            long datasourceId = repository.preflight(payload);
            database.preflight(datasourceId, payload);
            try (TableTaskDispatcher.Reservation slot = dispatcher.reserve()) {
                DataOperationRepository.Admission admission = repository.begin(datasourceId, idempotencyKey, payload);
                DataOperation operation = admission.getOperation();
                if (admission.isCreated()) {
                    return dispatch(slot, operation, payload);
                }
                return receipt(operation);
            }
        } catch (BizException exception) {
            // 并发提交可能在预检或预留容量期间已经持有占用，优先识别原幂等记录。
            return repository.byKey(key, idempotencyKey)
                    .map(operation -> receipt(operation.requireFingerprint(payload.getFingerprint())))
                    .orElseThrow(() -> exception);
        }
    }

    /** 已受理后的明确调度拒绝结束操作；意外结果保守保留占用。 */
    private DataOperationView.Receipt dispatch(TableTaskDispatcher.Reservation slot, DataOperation operation,
                                               AuthorizationSeedPayload payload) {
        try {
            slot.dispatch(() -> run(operation, payload));
            return receipt(operation);
        } catch (RuntimeException exception) {
            boolean rejected = exception instanceof BizException && ResourceErrorCode.EXECUTION_CAPACITY_EXHAUSTED
                    .equals(((BizException) exception).getErrorCode());
            DataOperation.Result result = rejected
                    ? DataOperation.Result.failed(ResourceErrorCode.EXECUTION_CAPACITY_EXHAUSTED)
                    : DataOperation.Result.unknown();
            persistResult(operation, result);
            return receipt(requireFound(repository.detail(operation.getDatasourceKey(), operation.getId())));
        }
    }

    /** 独立工作线程只执行一次，失败与结果不明均不重放。 */
    private void run(DataOperation operation, AuthorizationSeedPayload payload) {
        try {
            repository.requireExecution(operation, payload);
        } catch (BizException exception) {
            ResourceErrorCode error = exception.getErrorCode() instanceof ResourceErrorCode
                    ? (ResourceErrorCode) exception.getErrorCode() : ResourceErrorCode.INTERNAL_ERROR;
            persistResult(operation, DataOperation.Result.failed(error));
            return;
        } catch (RuntimeException exception) {
            persistResult(operation, DataOperation.Result.failed(ResourceErrorCode.INTERNAL_ERROR));
            return;
        }
        DataOperation.Result result;
        try {
            result = database.execute(operation.getDatasourceId(), payload);
        } catch (RuntimeException exception) {
            result = DataOperation.Result.unknown();
        }
        persistResult(operation, result);
    }

    /** 管理库回写失败只留下原操作待核验，不再次调用业务库。 */
    private void persistResult(DataOperation operation, DataOperation.Result result) {
        try {
            repository.finish(operation, result);
        } catch (RuntimeException exception) {
            log.warn("权限 Seed 结果回写未确认，操作ID={}", operation.getId());
        }
    }

    /** 按资源和正整数操作 ID 只读查询。 */
    public DataOperationView detail(String datasourceKey, long operationId) {
        if (operationId <= 0) {
            throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
        }
        return new DataOperationView(requireFound(repository.detail(new DatasourceKey(datasourceKey).getValue(), operationId)));
    }

    /** 幂等查找永远只读，未找到明确返回 404。 */
    public DataOperationView byKey(String datasourceKey, String idempotencyKey) {
        return new DataOperationView(requireFound(repository.byKey(new DatasourceKey(datasourceKey).getValue(),
                AuthorizationSeedPayload.requireIdempotencyKey(idempotencyKey))));
    }

    /** 对缺失记录统一给出脱敏错误。 */
    private static DataOperation requireFound(Optional<DataOperation> operation) {
        return operation.orElseThrow(() -> new BizException(ResourceErrorCode.RESOURCE_NOT_FOUND));
    }

    /** 保持既有操作 ID 字符串合同。 */
    private static DataOperationView.Receipt receipt(DataOperation operation) {
        return new DataOperationView.Receipt(Long.toString(operation.getId()), operation.getResult().getStatus());
    }
}
