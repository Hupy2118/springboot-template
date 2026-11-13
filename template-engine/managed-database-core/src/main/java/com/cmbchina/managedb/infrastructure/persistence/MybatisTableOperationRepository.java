package com.cmbchina.managedb.infrastructure.persistence;

import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.cmb.bee.commons.exceptions.BizException;
import com.cmbchina.managedb.domain.Actor;
import com.cmbchina.managedb.domain.CheckContext;
import com.cmbchina.managedb.domain.DatasourceResource;
import com.cmbchina.managedb.domain.ResourceErrorCode;
import com.cmbchina.managedb.domain.ResourceStatus;
import com.cmbchina.managedb.domain.TableBinding;
import com.cmbchina.managedb.domain.TableOperation;
import com.cmbchina.managedb.domain.repository.TableOperationRepository;
import com.cmbchina.managedb.domain.valueobject.DatasourceKey;
import com.cmbchina.managedb.infrastructure.persistence.mapper.DatasourceRegistrationMapper;
import com.cmbchina.managedb.infrastructure.persistence.mapper.TableBindingMapper;
import com.cmbchina.managedb.infrastructure.persistence.mapper.TableOperationMapper;
import com.cmbchina.managedb.infrastructure.persistence.po.DatasourceRegistrationPo;
import com.cmbchina.managedb.infrastructure.persistence.po.TableBindingPo;
import com.cmbchina.managedb.infrastructure.persistence.po.TableOperationPo;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 三表事务在同一实现内执行；不调用注册仓储的多个独立事务拼接原子操作。
 */
public final class MybatisTableOperationRepository implements TableOperationRepository {
    private final DatasourceRegistrationMapper resources;
    private final TableBindingMapper tables;
    private final TableOperationMapper operations;
    private final IdentifierGenerator ids;
    private final TableOperationCodec codec;
    private final TransactionTemplate read, write;

    /**
     * 统一短事务配置，JDBC 目标执行不进入这些事务。
     */
    public MybatisTableOperationRepository(DatasourceRegistrationMapper resources, TableBindingMapper tables, TableOperationMapper operations,
                                           IdentifierGenerator ids, TableOperationCodec codec, PlatformTransactionManager manager, int seconds) {
        if (seconds < 1) {
            throw new IllegalArgumentException("事务超时必须为正");
        }
        this.resources = resources;
        this.tables = tables;
        this.operations = operations;
        this.ids = ids;
        this.codec = codec;
        read = template(manager, seconds, true);
        write = template(manager, seconds, false);
    }

    /**
     * 每次读取使用新快照，检查前后读取才能发现期间提交。
     */
    private static TransactionTemplate template(PlatformTransactionManager manager, int seconds, boolean readonly) {
        TransactionTemplate t = new TransactionTemplate(manager);
        t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        t.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        t.setTimeout(seconds);
        t.setReadOnly(readonly);
        return t;
    }

    /**
     * 统一事务后翻译异常，冲突不触发目标 SQL 重放。
     */
    private <T> T tx(String stage, TransactionTemplate template, Supplier<T> action) {
        try {
            return template.execute(s -> action.get());
        } catch (BizException e) {
            throw e;
        } catch (DuplicateKeyException e) {
            throw new BizException(ResourceErrorCode.TABLE_BINDING_CONFLICT);
        } catch (RuntimeException e) {
            PersistenceDiagnostics.failure(stage, null, e);
            throw new BizException(ResourceErrorCode.INTERNAL_ERROR);
        }
    }

    /**
     * 服务统一主键生成。
     */
    @Override
    public long nextId() {
        return ids.nextId(null).longValue();
    }

    /**
     * 申请与执行准入串行；终态重复申请只返回原结果，不释放后来操作的占用。
     */
    @Override
    public TableOperation requestAbandon(String datasourceKey, long operationId, String reason, Actor actor) {
        return tx("repository.requestAbandon", write, () -> {
            DatasourceResource resource = application(resources.lock(datasourceKey));
            resource.requireActive();
            TableOperationPo row = operations.lock(datasourceKey, operationId);
            if (row == null) {
                throw new BizException(ResourceErrorCode.RESOURCE_NOT_FOUND);
            }
            TableOperation op = codec.restore(row);
            if (op.getDatasourceId() != resource.getDatasourceId() || Integer.valueOf(1).equals(row.getDeleted())) {
                throw new BizException(ResourceErrorCode.TABLE_BINDING_CONFLICT);
            }
            if (op.getStatus() == TableOperation.Status.SUCCEEDED || op.getStatus() == TableOperation.Status.FAILED) {
                return op;
            }
            if (!Long.valueOf(operationId).equals(resource.getActiveOperationId())) {
                throw new BizException(ResourceErrorCode.RESOURCE_BUSY);
            }
            if (op.getAbandonRequest() != null) {
                return op;
            }
            op.requestAbandon(reason, actor);
            requireOne(operations.updateResultByIdAndStatus(operationId, row.getStatus(), codec.result(op)));
            return op;
        });
    }

    /**
     * 恢复完整份数据源资源状态。
     */
    private static DatasourceResource application(DatasourceRegistrationPo row) {
        if (row == null) {
            throw new BizException(ResourceErrorCode.RESOURCE_NOT_FOUND);
        }
        return DatasourceResource.restore(new DatasourceKey(row.getDatasourceKey()), row.getDatasourceId(), row.getAppKey(), row.getAppName(),
                row.getCreatedBy(), row.getCreator(),
                ResourceStatus.valueOf(row.getStatus()), Integer.valueOf(1).equals(row.getDeleted()), row.getActiveOperationId());
    }

    /**
     * 恢复有效或保留目录。
     */
    private static TableBinding table(TableBindingPo row) {
        return new TableBinding(row.getDatasourceKey(), row.getDatasourceId(), row.getTableName(), TableBinding.Status.valueOf(row.getStatus()),
                Integer.valueOf(1).equals(row.getDeleted()));
    }

    /**
     * 资源与操作数在同一快照读取，计数包含全部保留记录。
     */
    @Override
    public CheckContext checkContext(String datasourceKey) {
        return tx("repository.checkContext", read,
                () -> new CheckContext(application(resources.selectByDatasourceKey(datasourceKey)), operations.countByDatasourceKey(datasourceKey)));
    }

    /**
     * 精确位置查询不隐式过滤软删除。
     */
    @Override
    public Optional<TableBinding> binding(long datasourceId, String name) {
        return tx("repository.binding", read,
                () -> Optional.ofNullable(tables.selectByDatasourceIdAndTableName(datasourceId, name)).map(MybatisTableOperationRepository::table));
    }

    /**
     * 额外表清单只读取本应用有效目录。
     */
    @Override
    public List<TableBinding> bindings(String datasourceKey) {
        return tx("repository.bindings", read,
                () -> tables.selectActiveByDatasourceKey(datasourceKey).stream().map(MybatisTableOperationRepository::table)
                        .collect(Collectors.toList()));
    }

    /**
     * 清理包含全部保留目录；异常软删归属由应用层明确拒绝，不能静默跳过。
     */
    @Override
    public List<TableBinding> cleanupBindings(String datasourceKey) {
        return tx("repository.cleanupBindings", read,
                () -> tables.selectByDatasourceKey(datasourceKey).stream().map(MybatisTableOperationRepository::table).collect(Collectors.toList()));
    }

    /**
     * 原子认领，固定应用、目录、操作的锁顺序。
     */
    @Override
    public void begin(TableOperation op, TableBinding.Status expectedStatus) {
        if (op.getMode() != TableOperation.Mode.CREATE && op.getMode() != TableOperation.Mode.REBUILD) {
            throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
        }
        tx("repository.begin", write, () -> {
            DatasourceResource resource = application(resources.lock(op.getDatasourceKey()));
            resource.requireActive();
            if (resource.getDatasourceId() != op.getDatasourceId()) {
                throw new BizException(ResourceErrorCode.TABLE_BINDING_CONFLICT);
            }
            resource.claim(op.getId());
            TableBindingPo row = tables.lock(op.getDatasourceId(), op.getTableName());
            if (row == null) {
                if (expectedStatus != null || op.getMode() == TableOperation.Mode.REBUILD) {
                    throw new BizException(ResourceErrorCode.TABLE_BINDING_CONFLICT);
                }
                row = new TableBindingPo();
                row.setId(ids.nextId(row).longValue());
                row.setDatasourceKey(op.getDatasourceKey());
                row.setDatasourceId(op.getDatasourceId());
                row.setTableName(op.getTableName());
                row.setStatus("REGISTERED");
                row.setCreatedBy(truncate(op.getCreatedBy(), 32));
                row.setCreator(truncate(op.getCreator(), 255));
                row.setDeleted(0);
                requireOne(tables.insert(row));
            } else {
                TableBinding bound = table(row);
                bound.requireOwner(op.getDatasourceKey(), op.getDatasourceId());
                if (expectedStatus != bound.getStatus()) {
                    throw new BizException(ResourceErrorCode.RESOURCE_BUSY);
                }
            }
            insertOperation(op);
            requireOne(resources.claim(op.getDatasourceKey(), op.getId()));
            return null;
        });
    }

    /**
     * 管理记录全批次原子受理；计数防止预检期间完成的受管操作被遗漏。
     */
    @Override
    public void beginBatch(TableOperation op, List<TableBinding.Status> expected, long expectedCount) {
        if (op.getMode() != TableOperation.Mode.CREATE_BATCH || expected.size() != op.getTableNames().size()) {
            throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
        }
        // 先检查转义后的 JSON 大小，不能在目录写入后才发现内容超限。
        codec.content(op);
        codec.result(op);
        tx("repository.beginBatch", write, () -> {
            DatasourceResource resource = application(resources.lock(op.getDatasourceKey()));
            resource.requireActive();
            resource.claim(op.getId());
            if (resource.getDatasourceId() != op.getDatasourceId()) {
                throw new BizException(ResourceErrorCode.TABLE_BINDING_CONFLICT);
            }
            long count = operations.countByDatasourceKey(op.getDatasourceKey());
            if (count != expectedCount) {
                throw new BizException(ResourceErrorCode.RESOURCE_BUSY);
            }
            for (String name : new TreeSet<>(op.getTableNames())) {
                TableBinding.Status status = expected.get(op.getTableNames().indexOf(name));
                TableBindingPo row = tables.lock(op.getDatasourceId(), name);
                if (row == null) {
                    if (status != null) {
                        throw new BizException(ResourceErrorCode.RESOURCE_BUSY);
                    }
                    row = new TableBindingPo();
                    row.setId(ids.nextId(row).longValue());
                    row.setDatasourceKey(op.getDatasourceKey());
                    row.setDatasourceId(op.getDatasourceId());
                    row.setTableName(name);
                    row.setStatus("REGISTERED");
                    row.setDeleted(0);
                    requireOne(tables.insert(row));
                } else {
                    TableBinding binding = table(row);
                    binding.requireOwner(op.getDatasourceKey(), op.getDatasourceId());
                    if (status != binding.getStatus()) {
                        throw new BizException(ResourceErrorCode.RESOURCE_BUSY);
                    }
                }
            }
            insertOperation(op);
            requireOne(resources.claim(op.getDatasourceKey(), op.getId()));
            return null;
        });
    }

    /**
     * 清理只认领已有目录，并在同一资源锁内检测预览之后的受管变更。
     */
    @Override
    public void beginCleanup(TableOperation op, long expectedOperationCount) {
        tx("repository.beginCleanup", write, () -> {
            if (op.getMode() != TableOperation.Mode.DROP && op.getMode() != TableOperation.Mode.RELEASE) {
                throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
            }
            DatasourceResource resource = application(resources.lock(op.getDatasourceKey()));
            resource.requireActive();
            resource.claim(op.getId());
            if (resource.getDatasourceId() != op.getDatasourceId()) {
                throw new BizException(ResourceErrorCode.TABLE_BINDING_CONFLICT);
            }
            long count = operations.countByDatasourceKey(op.getDatasourceKey());
            if (count != expectedOperationCount) {
                throw new BizException(ResourceErrorCode.CLEANUP_PREVIEW_CHANGED);
            }
            if (op.getMode() == TableOperation.Mode.RELEASE) {
                Set<String> current = tables.selectByDatasourceKey(op.getDatasourceKey())
                        .stream().map(row -> {
                            table(row).requireOwner(op.getDatasourceKey(), op.getDatasourceId());
                            return row.getTableName();
                        }).collect(Collectors.toSet());
                if (!current.equals(new HashSet<>(op.getTableNames()))) {
                    throw new BizException(ResourceErrorCode.CLEANUP_PREVIEW_CHANGED);
                }
            }
            for (String name : new TreeSet<>(op.getTableNames())) {
                TableBindingPo row = tables.lock(op.getDatasourceId(), name);
                if (row == null) {
                    throw new BizException(ResourceErrorCode.TABLE_BINDING_CONFLICT);
                }
                table(row).requireOwner(op.getDatasourceKey(), op.getDatasourceId());
            }
            insertOperation(op);
            requireOne(resources.claim(op.getDatasourceKey(), op.getId()));
            return null;
        });
    }

    /**
     * 操作内容和初始结果与占用一起写入，不能只返回内存受理状态。
     */
    private void insertOperation(TableOperation op) {
        TableOperationPo stored = new TableOperationPo();
        stored.setId(op.getId());
        stored.setDatasourceKey(op.getDatasourceKey());
        stored.setDatasourceId(op.getDatasourceId());
        stored.setActionType(TableOperationCodec.actionType(op.getMode()));
        stored.setExecutionJson(codec.content(op));
        stored.setExecutionResult(codec.result(op));
        stored.setStatus("RUNNING");
        stored.setCreatedBy(truncate(op.getCreatedBy(), 32));
        stored.setCreator(truncate(op.getCreator(), 255));
        stored.setCreatedAt(op.getCreatedAt());
        stored.setDeleted(0);
        requireOne(operations.insert(stored));
    }

    /**
     * 截断到列宽；null 原样返回 null，空串返回 null，避免未登录落占位值。
     */
    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() <= max ? t : t.substring(0, max);
    }

    /**
     * 写入前同时检查操作占用和三表关联，软删不能绕开执行。
     */
    private Locked lockCurrent(TableOperation op) {
        DatasourceResource resource = application(resources.lock(op.getDatasourceKey()));
        resource.requireActive();
        if (!Long.valueOf(op.getId()).equals(resource.getActiveOperationId()) || resource.getDatasourceId() != op.getDatasourceId()) {
            throw new BizException(ResourceErrorCode.RESOURCE_BUSY);
        }
        TableBindingPo row = null;
        for (String name : new TreeSet<>(op.getTableNames())) {
            TableBindingPo target = tables.lock(op.getDatasourceId(), name);
            if (target == null) {
                throw new BizException(ResourceErrorCode.TABLE_BINDING_CONFLICT);
            }
            table(target).requireOwner(op.getDatasourceKey(), op.getDatasourceId());
            if (name.equals(op.getTableName())) {
                row = target;
            }
        }
        TableOperationPo current = operations.lock(op.getDatasourceKey(), op.getId());
        if (current == null || !"RUNNING".equals(current.getStatus()) || op.getDatasourceId() != current.getDatasourceId()) {
            throw new BizException(ResourceErrorCode.RESOURCE_BUSY);
        }
        TableOperation persisted = codec.restore(current);
        if (persisted.getMode() != op.getMode() || !persisted.getTableNames().equals(op.getTableNames())) {
            throw new BizException(ResourceErrorCode.RESOURCE_BUSY);
        }
        return new Locked(row, current);
    }

    /**
     * 每步结果与物理状态一次提交。
     */
    @Override
    public void saveStep(TableOperation op, TableBinding.Status state) {
        tx("repository.saveStep", write, () -> {
            Locked locked = lockCurrent(op);
            TableBindingPo row = locked.table;
            TableOperation previous = codec.restore(locked.operation);
            int changed = 0, changedIndex = -1;
            op.retainAbandonRequest(previous);
            for (int i = 0; i < previous.getSteps().size(); i++) {
                TableOperation.StepStatus old = previous.getSteps().get(i).getStatus(), next = op.getSteps().get(i).getStatus();
                if (old != next) {
                    if (old != TableOperation.StepStatus.PENDING && old != TableOperation.StepStatus.RUNNING) {
                        throw new BizException(ResourceErrorCode.RESOURCE_BUSY);
                    }
                    changed++;
                    changedIndex = i;
                }
                // 准入与放弃共用资源行锁；一旦准入提交，该步属于必须等待结果的在途步骤。
                if (old == TableOperation.StepStatus.PENDING && next == TableOperation.StepStatus.RUNNING) {
                    previous.requireExecutionAllowed();
                }
            }
            if (changed != 1) {
                throw new BizException(ResourceErrorCode.RESOURCE_BUSY);
            }
            if (state != null) {
                if (op.getMode() == TableOperation.Mode.RELEASE || op.getMode() == TableOperation.Mode.CREATE_BATCH) {
                    if (changedIndex >= op.getTableNames().size()) {
                        throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
                    }
                    row = tables.lock(op.getDatasourceId(), op.getTableNames().get(changedIndex));
                }
                if (row == null) {
                    throw new BizException(ResourceErrorCode.TABLE_BINDING_CONFLICT);
                }
                TableBinding binding = table(row);
                if (state == TableBinding.Status.CREATED) {
                    binding.created();
                } else if (state == TableBinding.Status.DROPPED) {
                    binding.dropped();
                } else {
                    throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
                }
                requireOne(tables.updateStatusById(row.getId(), binding.getStatus().name()));
            }
            requireOne(operations.updateResultByIdAndStatus(op.getId(), "RUNNING", codec.result(op)));
            return null;
        });
    }

    /**
     * 终态与占用释放一次提交，未知始终保留占用。
     */
    @Override
    public void finish(TableOperation op) {
        tx("repository.finish", write, () -> {
            Locked locked = lockCurrent(op);
            op.retainAbandonRequest(codec.restore(locked.operation));
            if (op.getStatus() == TableOperation.Status.RUNNING) {
                throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
            }
            requireOne(operations.updateStatusAndResultByIdAndStatus(op.getId(), "RUNNING", op.getStatus().name(), codec.result(op)));
            if (op.getMode() == TableOperation.Mode.RELEASE && op.getStatus() == TableOperation.Status.SUCCEEDED) {
                TableOperation persisted = codec.restore(locked.operation);
                if (persisted.getSteps().stream().anyMatch(step -> step.getStatus() != TableOperation.StepStatus.SUCCEEDED)) {
                    throw new BizException(ResourceErrorCode.RESOURCE_BUSY);
                }
                for (String name : op.getTableNames()) {
                    if (table(tables.lock(op.getDatasourceId(), name)).getStatus() != TableBinding.Status.DROPPED) {
                        throw new BizException(ResourceErrorCode.RESOURCE_BUSY);
                    }
                }
                DatasourceResource resource = application(resources.lock(op.getDatasourceKey()));
                resource.releaseResources(op.getId());
                requireOne(resources.completeRelease(op.getDatasourceKey(), op.getId()));
            } else if (op.getStatus() != TableOperation.Status.UNKNOWN) {
                requireOne(resources.release(op.getDatasourceKey(), op.getId()));
            }
            return null;
        });
    }

    /**
     * 详情查询始终按 datasourceKey 关联。
     */
    @Override
    public Optional<TableOperation> detail(String datasourceKey, long id) {
        return tx("repository.detail", read, () -> Optional.ofNullable(operations.selectByDatasourceKeyAndId(datasourceKey, id)).map(codec::restore));
    }

    /**
     * 限定数量的历史只读查询，LIMIT 仅拼接已验证整数。
     */
    @Override
    public List<TableOperation> recent(String datasourceKey, int limit) {
        if (limit < 1 || limit > 100) {
            throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
        }
        return tx("repository.recent", read,
                () -> operations.selectRecentByDatasourceKey(datasourceKey, limit).stream().map(codec::restore).collect(Collectors.toList()));
    }

    /**
     * 一次锁定的目录及操作只在当前事务内使用。
     */
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    private static final class Locked {
        private final TableBindingPo table;
        private final TableOperationPo operation;
    }

    /**
     * 条件写入失败必须回滚，不能假成功。
     */
    private static void requireOne(int rows) {
        if (rows != 1) {
            throw new BizException(ResourceErrorCode.RESOURCE_BUSY);
        }
    }
}
