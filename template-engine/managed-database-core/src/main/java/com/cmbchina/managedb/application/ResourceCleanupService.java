package com.cmbchina.managedb.application;

import com.cmb.bee.commons.exceptions.BizException;
import com.cmbchina.managedb.application.view.ResourceCleanupPreview;
import com.cmbchina.managedb.application.view.TableOperationReceipt;
import com.cmbchina.managedb.application.view.TableOperationView;
import com.cmbchina.managedb.domain.Actor;
import com.cmbchina.managedb.domain.CheckContext;
import com.cmbchina.managedb.domain.DatabaseSchemaPort;
import com.cmbchina.managedb.domain.DatasourceResource;
import com.cmbchina.managedb.domain.ResourceErrorCode;
import com.cmbchina.managedb.domain.SqlExecutionResult;
import com.cmbchina.managedb.domain.TableBinding;
import com.cmbchina.managedb.domain.TableOperation;
import com.cmbchina.managedb.domain.TableStructure;
import com.cmbchina.managedb.domain.TableTaskDispatcher;
import com.cmbchina.managedb.domain.repository.TableOperationRepository;
import com.cmbchina.managedb.domain.valueobject.DatasourceKey;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 删除精确归属表及回收整份数据源资源，目标执行不进入管理事务或 HTTP 线程。
 */
@Service
@RequiredArgsConstructor
public class ResourceCleanupService {
    private final TableOperationRepository repository;
    private final DatabaseSchemaPort schemas;
    private final TableTaskDispatcher dispatcher;

    /**
     * 只读生成确认清单，绝不执行 DROP 或改变资源状态。
     */
    public ResourceCleanupPreview preview(String rawDatasourceKey, String scope, String tableName) {
        return snapshot(rawDatasourceKey, scope, tableName).view;
    }

    /**
     * 明确确认与当前指纹一致后认领占用，不能用空确认或旧预览删除新资源。
     */
    public TableOperationReceipt confirm(String rawDatasourceKey, String scope, String tableName,
                                         CleanupConfirmation confirmation, Actor actor) {
        if (confirmation == null || !confirmation.isConfirmed() || !confirmation.isAllowDataLoss()
                || confirmation.getPreviewHash() == null || !confirmation.getPreviewHash().matches("[0-9a-f]{64}")) {
            throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
        }
        Snapshot snapshot = snapshot(rawDatasourceKey, scope, tableName);
        if (!MessageDigest.isEqual(confirmation.getPreviewHash().getBytes(StandardCharsets.UTF_8),
                snapshot.view.getPreviewHash().getBytes(StandardCharsets.UTF_8))) {
            throw new BizException(ResourceErrorCode.CLEANUP_PREVIEW_CHANGED);
        }
        TableOperation op = TableOperation.cleanup(repository.nextId(), snapshot.view.getDatasourceKey(), snapshot.datasourceId,
                "TABLE".equals(scope) ? TableOperation.Mode.DROP : TableOperation.Mode.RELEASE, snapshot.view.getTableNames(), actor);
        try (TableTaskDispatcher.Reservation slot = dispatcher.reserve()) {
            repository.beginCleanup(op, snapshot.operationCount);
            try {
                slot.dispatch(() -> run(op.getDatasourceKey(), op.getId()));
            } catch (BizException rejected) {
                fail(op, 0, rejected.getErrorCode().getErrorCodeStr(), false, false);
                throw rejected;
            }
        }
        return new TableOperationReceipt(op.getId());
    }

    /**
     * 状态读取允许已回收应用查询原结果，但不能读取其他数据源资源的操作。
     */
    public TableOperationView status(String rawDatasourceKey, long operationId) {
        String datasourceKey = new DatasourceKey(rawDatasourceKey).getValue();
        if (operationId < 1) {
            throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
        }
        TableOperation op = repository.detail(datasourceKey, operationId).orElseThrow(() -> new BizException(ResourceErrorCode.RESOURCE_NOT_FOUND));
        if (op.getMode() != TableOperation.Mode.DROP && op.getMode() != TableOperation.Mode.RELEASE) {
            throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
        }
        return new TableOperationView(op);
    }

    /**
     * 用操作计数、真实存在性和依赖关系冻结范围；不从表名前缀扫描或认领目录外表。
     */
    private Snapshot snapshot(String rawDatasourceKey, String scope, String tableName) {
        String datasourceKey = new DatasourceKey(rawDatasourceKey).getValue();
        if (!"TABLE".equals(scope) && !"DATASOURCE".equals(scope)) {
            throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
        }
        if ("DATASOURCE".equals(scope) && tableName != null) {
            throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
        }
        CheckContext before = repository.checkContext(datasourceKey);
        DatasourceResource resource = before.getResource();
        resource.requireActive();
        if (resource.getActiveOperationId() != null) {
            throw new BizException(ResourceErrorCode.RESOURCE_BUSY);
        }
        List<TableBinding> bindings;
        if ("TABLE".equals(scope)) {
            if (tableName == null || tableName.isEmpty() || tableName.length() > 64) {
                throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
            }
            String key = schemas.tableKey(resource.getDatasourceId(), tableName);
            bindings = new ArrayList<>(Collections.singletonList(repository.binding(resource.getDatasourceId(), key)
                    .orElseThrow(() -> new BizException(ResourceErrorCode.TABLE_BINDING_CONFLICT))));
        } else {
            bindings = new ArrayList<>(repository.cleanupBindings(datasourceKey));
        }
        if (bindings.size() > 200) {
            throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
        }
        Map<String, Set<String>> references = new TreeMap<>();
        StringBuilder signature = new StringBuilder();
        part(signature, datasourceKey);
        part(signature, scope);
        part(signature, Long.toString(resource.getDatasourceId()));
        part(signature, Long.toString(before.getOperationCount()));
        bindings.sort(Comparator.comparing(TableBinding::getTableName));
        for (TableBinding binding : bindings) {
            binding.requireOwner(datasourceKey, resource.getDatasourceId());
            String name = binding.getTableName();
            if (references.containsKey(name)) {
                throw new BizException(ResourceErrorCode.TABLE_BINDING_CONFLICT);
            }
            Optional<TableStructure> actual = schemas.readTable(resource.getDatasourceId(), name);
            Set<String> targets = new TreeSet<>();
            if (actual.isPresent()) {
                for (TableStructure.ForeignKey key : actual.get().getForeignKeys()) {
                    targets.add(schemas.tableKey(resource.getDatasourceId(), key.getTargetTable()));
                }
            }
            references.put(name, targets);
            part(signature, name);
            part(signature, binding.getStatus().name());
            part(signature, Boolean.toString(actual.isPresent()));
            for (String target : targets) {
                part(signature, target);
            }
        }
        List<String> ordered = deletionOrder(references);
        CheckContext after = repository.checkContext(datasourceKey);
        after.getResource().requireActive();
        if (after.getResource().getActiveOperationId() != null || after.getResource().getDatasourceId() != resource.getDatasourceId()
                || after.getOperationCount() != before.getOperationCount()) {
            throw new BizException(ResourceErrorCode.CLEANUP_PREVIEW_CHANGED);
        }
        return new Snapshot(new ResourceCleanupPreview(datasourceKey, scope, ordered, hash(signature.toString())), resource.getDatasourceId(),
                before.getOperationCount());
    }

    /**
     * 优先删除引用其他表的子表；循环依赖停止，不关闭外键检查。
     */
    private List<String> deletionOrder(Map<String, Set<String>> references) {
        Set<String> remaining = new TreeSet<>(references.keySet());
        List<String> ordered = new ArrayList<>();
        while (!remaining.isEmpty()) {
            String selected = null;
            for (String candidate : remaining) {
                boolean referenced = false;
                for (String other : remaining) {
                    if (references.get(other).contains(candidate)) {
                        referenced = true;
                        break;
                    }
                }
                if (!referenced) {
                    selected = candidate;
                    break;
                }
            }
            if (selected == null) {
                throw new BizException(ResourceErrorCode.TABLE_REFERENCED);
            }
            ordered.add(selected);
            remaining.remove(selected);
        }
        return ordered;
    }

    /**
     * 工作任务只使用已持久化范围，每张表删除后回读；失败停止后续表。
     */
    private void run(String datasourceKey, long operationId) {
        TableOperation op = null;
        int index = 0;
        try {
            op = repository.detail(datasourceKey, operationId).orElseThrow(() -> new BizException(ResourceErrorCode.RESOURCE_NOT_FOUND));
            if (op.getStatus() != TableOperation.Status.RUNNING || op.getSteps().stream()
                    .anyMatch(step -> step.getStatus() != TableOperation.StepStatus.PENDING)) {
                return;
            }
            if (op.getMode() != TableOperation.Mode.DROP && op.getMode() != TableOperation.Mode.RELEASE) {
                throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
            }
            DatasourceResource resource = repository.checkContext(datasourceKey).getResource();
            resource.requireActive();
            if (resource.getDatasourceId() != op.getDatasourceId() || !Long.valueOf(operationId).equals(resource.getActiveOperationId())) {
                throw new BizException(ResourceErrorCode.RESOURCE_BUSY);
            }
            for (index = 0; index < op.getSteps().size(); index++) {
                if (op.getTableNames().isEmpty()) {
                    op.markRunning(index);
                    repository.saveStep(op, null);
                    op.complete(index, TableOperation.StepStatus.SUCCEEDED, null, evidence(null, false, false));
                    repository.saveStep(op, null);
                } else {
                    drop(op, index);
                }
            }
            op.finish(TableOperation.Status.SUCCEEDED, null);
            repository.finish(op);
        } catch (CleanupFailure failure) {
            fail(op, index, failure.code, failure.unknown, failure.executed);
        } catch (BizException failure) {
            fail(op, index, failure.getErrorCode().getErrorCodeStr(), false, false);
        } catch (RuntimeException failure) {
            fail(op, index, "EXECUTION_UNKNOWN", true, null);
        }
    }

    /**
     * SQL 发出前保存准入，明确结果经回读后才写入 DROPPED；未知禁止重发。
     */
    private void drop(TableOperation op, int index) {
        String name = op.getTableNames().get(index);
        repository.binding(op.getDatasourceId(), name).orElseThrow(() -> new BizException(ResourceErrorCode.TABLE_BINDING_CONFLICT))
                .requireOwner(op.getDatasourceKey(), op.getDatasourceId());
        boolean exists = schemas.readTable(op.getDatasourceId(), name).isPresent();
        if (exists && schemas.hasIncomingReferences(op.getDatasourceId(), name)) {
            throw new BizException(ResourceErrorCode.TABLE_REFERENCED);
        }
        op.markRunning(index);
        try {
            repository.saveStep(op, null);
        } catch (BizException e) {
            if (e.getErrorCode() == ResourceErrorCode.OPERATION_ABANDONED) {
                throw new CleanupFailure("OPERATION_ABANDONED", false, false);
            }
            throw new CleanupFailure("EXECUTION_UNKNOWN", true, false);
        } catch (RuntimeException e) {
            throw new CleanupFailure("EXECUTION_UNKNOWN", true, false);
        }
        SqlExecutionResult result = null;
        if (exists) {
            try {
                result = schemas.execute(op.getDatasourceId(), "DROP TABLE `" + name.replace("`", "``") + "`");
            } catch (RuntimeException e) {
                throw new CleanupFailure("EXECUTION_UNKNOWN", true, null);
            }
            if (result.getOutcome() == SqlExecutionResult.Outcome.UNKNOWN) {
                throw new CleanupFailure("EXECUTION_UNKNOWN", true, null);
            }
            if (result.getOutcome() == SqlExecutionResult.Outcome.NOT_SENT) {
                throw new CleanupFailure(result.getErrorCode(), false, false);
            }
        }
        boolean after;
        try {
            after = schemas.readTable(op.getDatasourceId(), name).isPresent();
        } catch (RuntimeException e) {
            throw new CleanupFailure("EXECUTION_UNKNOWN", true, exists);
        }
        boolean success = !after && (result == null || result.getOutcome() != SqlExecutionResult.Outcome.REJECTED);
        String code = success ? null : "SQL_EXECUTION_FAILED";
        op.complete(index, success ? TableOperation.StepStatus.SUCCEEDED : TableOperation.StepStatus.FAILED, code, evidence(name, exists, after));
        try {
            repository.saveStep(op, after ? TableBinding.Status.CREATED : TableBinding.Status.DROPPED);
        } catch (RuntimeException e) {
            throw new CleanupFailure("EXECUTION_UNKNOWN", true, exists);
        }
        if (!success) {
            throw new CleanupFailure(code, false, exists);
        }
    }

    /**
     * 保存部分完成事实，未知占用保留；管理库写失败不自动再执行或强行释放。
     */
    private void fail(TableOperation op, int index, String code, boolean unknown, Boolean executed) {
        if (op == null || op.getStatus() != TableOperation.Status.RUNNING) {
            return;
        }
        try {
            if (index < op.getSteps().size()) {
                TableOperation.StepStatus status = op.getSteps().get(index).getStatus();
                if (status == TableOperation.StepStatus.PENDING || status == TableOperation.StepStatus.RUNNING) {
                    String name = op.getTableNames().isEmpty() ? null : op.getTableNames().get(index);
                    op.complete(index, unknown ? TableOperation.StepStatus.UNKNOWN : TableOperation.StepStatus.FAILED, code,
                            evidence(name, executed, null));
                    repository.saveStep(op, null);
                }
            }
            op.finish(unknown ? TableOperation.Status.UNKNOWN : TableOperation.Status.FAILED, code);
            repository.finish(op);
        } catch (RuntimeException unavailable) {/* 最后持久化状态及占用留给人工核对，不能伪造清理成功。 */}
    }

    /**
     * 只记录精确表名和执行证据，不保存驱动异常、凭据或请求原文。
     */
    private static Map<String, Object> evidence(String name, Boolean executed, Boolean exists) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("tableName", name);
        value.put("sqlExecuted", executed);
        value.put("tableExists", exists);
        return value;
    }

    /**
     * 长度前缀避免各确认字段拼接歧义。
     */
    private static void part(StringBuilder value, String part) {
        value.append(part.length()).append(':').append(part);
    }

    /**
     * 计算不含密钥的确认指纹，拒绝依赖调用方提供的删除清单。
     */
    private static String hash(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte item : bytes) {
                hex.append(String.format(Locale.ROOT, "%02x", item & 255));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用");
        }
    }

    /**
     * 内存确认依据只用于认领，不能作为资源已经删除的证据。
     */
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    private static final class Snapshot {
        private final ResourceCleanupPreview view;
        private final long datasourceId, operationCount;
    }

    /**
     * 区分未发送、明确拒绝和未知结果，错误正文不向外传播。
     */
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    private static final class CleanupFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;
        private final boolean unknown;
        private final Boolean executed;
    }
}
