package com.cmbchina.managedb.application;

import com.cmb.bee.commons.exceptions.BizException;
import com.cmbchina.managedb.application.view.TableOperationReceipt;
import com.cmbchina.managedb.application.view.TableOperationView;
import com.cmbchina.managedb.domain.Actor;
import com.cmbchina.managedb.domain.CheckContext;
import com.cmbchina.managedb.domain.DatabaseSchemaPort;
import com.cmbchina.managedb.domain.DatasourceResource;
import com.cmbchina.managedb.domain.ParsedCreateTable;
import com.cmbchina.managedb.domain.ResourceErrorCode;
import com.cmbchina.managedb.domain.SqlExecutionResult;
import com.cmbchina.managedb.domain.StructureDifference;
import com.cmbchina.managedb.domain.TableBinding;
import com.cmbchina.managedb.domain.TableOperation;
import com.cmbchina.managedb.domain.TableStructure;
import com.cmbchina.managedb.domain.TableStructureComparator;
import com.cmbchina.managedb.domain.TableTaskDispatcher;
import com.cmbchina.managedb.domain.repository.TableOperationRepository;
import com.cmbchina.managedb.domain.valueobject.DatasourceKey;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 管理创建与重建，用户 SQL 不在管理事务或 HTTP 线程内执行。
 */
@Service
@RequiredArgsConstructor
public class TableOperationService {
    private final TableOperationRepository repository;
    private final DatabaseSchemaPort schemas;
    private final TableSqlSupport support;
    private final TableStructureComparator comparator;
    private final TableTaskDispatcher dispatcher;

    /**
     * 完整预检查后认领并转交任务，失败不伪造受理成功。
     */
    public TableOperationReceipt submit(String rawDatasourceKey, String rawMode, String sql, boolean allowDataLoss, Actor actor) {
        String datasourceKey = new DatasourceKey(rawDatasourceKey).getValue();
        TableOperation.Mode mode = parseMode(rawMode);
        if (mode != TableOperation.Mode.CREATE && mode != TableOperation.Mode.REBUILD) {
            throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
        }
        if (mode == TableOperation.Mode.REBUILD && !allowDataLoss) {
            throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
        }
        CheckContext context = repository.checkContext(datasourceKey);
        DatasourceResource resource = context.getResource();
        resource.requireActive();
        if (resource.getActiveOperationId() != null) {
            throw new BizException(ResourceErrorCode.RESOURCE_BUSY);
        }
        ParsedCreateTable parsed = support.parse(resource.getDatasourceId(), sql);
        TableStructure expected = parsed.getStructure();
        String name = expected.getTableName();
        Optional<TableBinding> binding = repository.binding(resource.getDatasourceId(), name);
        if (binding.isPresent()) {
            binding.get().requireOwner(datasourceKey, resource.getDatasourceId());
        }
        Optional<TableStructure> actual = schemas.readTable(resource.getDatasourceId(), name);
        boolean satisfied = validateModePrecondition(mode, binding, actual, resource, expected, name);
        support.requireReferences(datasourceKey, resource.getDatasourceId(), expected);
        TableOperation op = TableOperation.start(repository.nextId(), datasourceKey,
                resource.getDatasourceId(), name, sql, mode, satisfied, actor);
        try (TableTaskDispatcher.Reservation slot = dispatcher.reserve()) {
            repository.begin(op, binding.map(TableBinding::getStatus).orElse(null));
            try {
                slot.dispatch(() -> run(datasourceKey, op.getId()));
            } catch (BizException rejected) {
                // 未发送 SQL 的明确调度拒绝保留真实原因，不能混写为资源占用。
                String code = rejected.getErrorCode().getErrorCodeStr();
                op.complete(0, TableOperation.StepStatus.FAILED, code, evidence(false, false, Collections.emptyList()));
                op.finish(TableOperation.Status.FAILED, code);
                repository.finish(op);
                throw rejected;
            }
        }
        return new TableOperationReceipt(op.getId());
    }

    /**
     * 解析并限定操作模式，仅允许 CREATE 与 REBUILD。
     */
    private TableOperation.Mode parseMode(String rawMode) {
        try {
            return TableOperation.Mode.valueOf(rawMode);
        } catch (Exception e) {
            throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
        }
    }

    /**
     * 按模式校验绑定与实际结构，返回已有结构是否已满足。
     */
    private boolean validateModePrecondition(TableOperation.Mode mode, Optional<TableBinding> binding,
                                             Optional<TableStructure> actual, DatasourceResource resource,
                                             TableStructure expected, String name) {
        boolean satisfied = false;
        if (mode == TableOperation.Mode.REBUILD) {
            if (!binding.isPresent()) {
                throw new BizException(ResourceErrorCode.TABLE_BINDING_CONFLICT);
            }
            if (schemas.hasIncomingReferences(resource.getDatasourceId(), name)) {
                throw new BizException(ResourceErrorCode.TABLE_BINDING_CONFLICT);
            }
        } else if (actual.isPresent()) {
            if (!binding.isPresent() || binding.get().getStatus() != TableBinding.Status.CREATED) {
                throw new BizException(ResourceErrorCode.TABLE_BINDING_CONFLICT);
            }
            if (!comparator.compare(expected, actual.get()).isEmpty()) {
                throw new BizException(ResourceErrorCode.STRUCTURE_MISMATCH);
            }
            satisfied = true;
        }
        return satisfied;
    }

    /**
     * 工作线程只读取已保存的内容，不使用临时请求或重新分配位置。
     */
    void run(String datasourceKey, long id) {
        TableOperation op = null;
        int index = 0;
        try {
            op = repository.detail(datasourceKey, id)
                    .orElseThrow(() -> new BizException(ResourceErrorCode.RESOURCE_NOT_FOUND));
            if (op.getStatus() != TableOperation.Status.RUNNING
                    || op.getSteps().stream().anyMatch(s -> s.getStatus() != TableOperation.StepStatus.PENDING)) {
                return;
            }
            DatasourceResource resource = repository.checkContext(datasourceKey).getResource();
            resource.requireActive();
            if (resource.getDatasourceId() != op.getDatasourceId()
                    || !Long.valueOf(id).equals(resource.getActiveOperationId())) {
                throw failure("RESOURCE_BUSY", false, false);
            }
            if (op.getMode() == TableOperation.Mode.CREATE_BATCH) {
                for (index = 0; index < op.getTableNames().size(); index++) {
                    String name = op.getTableNames().get(index);
                    TableBinding binding = repository.binding(op.getDatasourceId(), name)
                            .orElseThrow(() -> new BizException(ResourceErrorCode.TABLE_BINDING_CONFLICT));
                    binding.requireOwner(datasourceKey, op.getDatasourceId());
                    TableStructure expected = support.parse(op.getDatasourceId(), op.getBatchSqls().get(index)).getStructure();
                    if (!name.equals(expected.getTableName())) {
                        throw failure("TABLE_BINDING_CONFLICT", false, false);
                    }
                    support.requireReferences(datasourceKey, op.getDatasourceId(), expected);
                    step(op, index, expected, false);
                }
                index = op.getSteps().size() - 1;
            } else {
                TableBinding binding = repository.binding(op.getDatasourceId(), op.getTableName())
                        .orElseThrow(() -> new BizException(ResourceErrorCode.TABLE_BINDING_CONFLICT));
                binding.requireOwner(datasourceKey, op.getDatasourceId());
                ParsedCreateTable parsed = support.parse(op.getDatasourceId(), op.getCreateSql());
                TableStructure expected = parsed.getStructure();
                if (!expected.getTableName().equals(op.getTableName())) {
                    throw failure("TABLE_BINDING_CONFLICT", false, false);
                }
                support.requireReferences(datasourceKey, op.getDatasourceId(), expected);
                if (op.getMode() == TableOperation.Mode.REBUILD) {
                    step(op, 0, expected, true);
                    index = 1;
                }
                step(op, index, expected, false);
            }
            op.finish(TableOperation.Status.SUCCEEDED, null);
            if (op.getMode() == TableOperation.Mode.CREATE_BATCH) {
                finishBatch(op);
            } else {
                repository.finish(op);
            }
        } catch (Failure f) {
            handleFailure(op, index, f);
        } catch (BizException e) {
            handleFailure(op, index, failure(e.getErrorCode().getErrorCodeStr(), false, false));
        } catch (RuntimeException e) {
            handleFailure(op, index, failure("EXECUTION_UNKNOWN", true, null));
        }
    }

    /**
     * 每步先保存 RUNNING，再发 SQL；发出后的回读失败按未知处理。
     */
    private void step(TableOperation op, int index, TableStructure expected, boolean drop) {
        String name = op.getMode() == TableOperation.Mode.CREATE_BATCH ? op.getTableNames().get(index) : op.getTableName();
        String createSql = op.getMode() == TableOperation.Mode.CREATE_BATCH ? op.getBatchSqls().get(index) : op.getCreateSql();
        boolean satisfied = op.getMode() == TableOperation.Mode.CREATE_BATCH ? op.getBatchSatisfied().get(index) : op.isExistingSatisfied();
        if (drop && schemas.hasIncomingReferences(op.getDatasourceId(), name)) {
            throw failure("TABLE_BINDING_CONFLICT", false, false);
        }
        Optional<TableStructure> before = schemas.readTable(op.getDatasourceId(), name);
        boolean execute;
        if (drop) {
            execute = before.isPresent();
        } else if (satisfied) {
            if (!before.isPresent() || !comparator.compare(expected, before.get()).isEmpty()) {
                throw failure("STRUCTURE_MISMATCH", false, false);
            }
            execute = false;
        } else {
            if (before.isPresent()) {
                throw failure("TABLE_BINDING_CONFLICT", false, false);
            }
            execute = true;
        }
        op.markRunning(index);
        try {
            repository.saveStep(op, null);
        } catch (BizException e) {
            if (e.getErrorCode() == ResourceErrorCode.OPERATION_ABANDONED) {
                throw failure("OPERATION_ABANDONED", false, false);
            }
            throw failure("EXECUTION_UNKNOWN", true, false);
        } catch (RuntimeException e) {
            throw failure("EXECUTION_UNKNOWN", true, false);
        }
        SqlExecutionResult sql = null;
        if (execute) {
            try {
                sql = schemas.execute(op.getDatasourceId(), drop ? "DROP TABLE `" + name.replace("`", "``") + "`" : createSql);
            } catch (RuntimeException e) {
                throw failure("EXECUTION_UNKNOWN", true, null);
            }
            if (sql.getOutcome() == SqlExecutionResult.Outcome.UNKNOWN) {
                throw failure("EXECUTION_UNKNOWN", true, null);
            }
            if (sql.getOutcome() == SqlExecutionResult.Outcome.NOT_SENT) {
                throw failure(sql.getErrorCode(), false, false);
            }
        }
        Optional<TableStructure> after;
        try {
            after = schemas.readTable(op.getDatasourceId(), name);
        } catch (RuntimeException e) {
            throw failure("EXECUTION_UNKNOWN", true, execute ? Boolean.TRUE : Boolean.FALSE);
        }
        if (sql != null && sql.getOutcome() == SqlExecutionResult.Outcome.REJECTED) {
            throw failure(sql.getErrorCode(), false, true);
        }
        List<StructureDifference> differences =
                drop ? Collections.emptyList() : after.isPresent() ? comparator.compare(expected, after.get()) : Collections.emptyList();
        boolean success = drop ? !after.isPresent() : after.isPresent() && differences.isEmpty();
        op.complete(index, success ? TableOperation.StepStatus.SUCCEEDED : TableOperation.StepStatus.FAILED, success ? null : "STRUCTURE_MISMATCH",
                evidence(execute, after.isPresent(), differences));
        TableBinding.Status physical = after.isPresent() ? TableBinding.Status.CREATED : TableBinding.Status.DROPPED;
        try {
            repository.saveStep(op, physical);
        } catch (RuntimeException e) {
            throw failure("EXECUTION_UNKNOWN", true, execute);
        }
        if (!success) {
            throw failure("STRUCTURE_MISMATCH", false, execute);
        }
    }

    /**
     * 终态保存失败时从持久化快照登记未知；无法保存则保留最后证据和占用。
     */
    private void finishBatch(TableOperation op) {
        try {
            repository.finish(op);
        } catch (RuntimeException unavailable) {
            try {
                TableOperation saved = repository.detail(op.getDatasourceKey(), op.getId())
                        .orElseThrow(() -> new BizException(ResourceErrorCode.RESOURCE_NOT_FOUND));
                if (saved.getStatus() == TableOperation.Status.RUNNING) {
                    saved.finish(TableOperation.Status.UNKNOWN, "EXECUTION_UNKNOWN");
                    repository.finish(saved);
                }
            } catch (RuntimeException ignored) {/* 不重发 SQL，不清除占用，等待人工核对。 */}
        }
    }

    /**
     * 依据当前结构决定本步是否需要发 SQL。
     */
    private boolean decideExecution(TableOperation op, TableStructure expected,
                                    Optional<TableStructure> before, boolean drop) {
        if (drop) {
            return before.isPresent();
        }
        if (op.isExistingSatisfied()) {
            if (!before.isPresent() || !comparator.compare(expected, before.get()).isEmpty()) {
                throw failure("STRUCTURE_MISMATCH", false, false);
            }
            return false;
        }
        if (before.isPresent()) {
            throw failure("TABLE_BINDING_CONFLICT", false, false);
        }
        return true;
    }

    /**
     * 保存可证明的失败，保存链路不确定时只尝试留未知占用，不重放 SQL。
     */
    private void handleFailure(TableOperation op, int index, Failure failure) {
        if (op == null || op.getStatus() != TableOperation.Status.RUNNING) {
            return;
        }
        try {
            TableOperation.StepStatus status = op.getSteps().get(index).getStatus();
            if (status == TableOperation.StepStatus.PENDING || status == TableOperation.StepStatus.RUNNING) {
                op.complete(index, failure.unknown ? TableOperation.StepStatus.UNKNOWN : TableOperation.StepStatus.FAILED,
                        failure.code, evidence(failure.executed, null, Collections.emptyList()));
                repository.saveStep(op, null);
            }
            op.finish(failure.unknown ? TableOperation.Status.UNKNOWN : TableOperation.Status.FAILED, failure.code);
            repository.finish(op);
        } catch (RuntimeException ignored) {
            // 若连未知都无法保存，保留数据库最后状态与占用，等待人工核对。
            if (op.getStatus() == TableOperation.Status.RUNNING) {
                try {
                    op.finish(TableOperation.Status.UNKNOWN, "EXECUTION_UNKNOWN");
                    repository.finish(op);
                } catch (RuntimeException unavailable) {
                    // 禁止清占用或再次发 SQL。
                }
            }
        }
    }

    /**
     * 有限回读证据，未知是否发送时用 null 而非伪造 false。
     */
    private static Map<String, Object> evidence(Boolean executed, Boolean exists, List<StructureDifference> differences) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sqlExecuted", executed);
        result.put("tableExists", exists);
        result.put("differences", differences);
        return result;
    }

    /**
     * 构造不会带出 SQL 或驱动异常的内部执行结论。
     */
    private static Failure failure(String code, boolean unknown, Boolean executed) {
        return new Failure(code, unknown, executed);
    }

    /**
     * 查询原操作，绝不触发执行。
     */
    public TableOperationView detail(String rawDatasourceKey, long id) {
        String datasourceKey = new DatasourceKey(rawDatasourceKey).getValue();
        if (id < 1) {
            throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
        }
        return new TableOperationView(repository.detail(datasourceKey, id)
                .orElseThrow(() -> new BizException(ResourceErrorCode.RESOURCE_NOT_FOUND)));
    }

    /**
     * 查询最近保留历史，数量由接口及仓储双重限定。
     */
    public List<TableOperationView> recent(String rawDatasourceKey, int limit) {
        String datasourceKey = new DatasourceKey(rawDatasourceKey).getValue();
        repository.checkContext(datasourceKey);
        return repository.recent(datasourceKey, limit).stream()
                .map(TableOperationView::new).collect(Collectors.toList());
    }

    /**
     * 内部结论区别明确失败和未知，不作为原始异常向外输出。
     */
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    private static final class Failure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;
        private final boolean unknown;
        private final Boolean executed;
    }
}
