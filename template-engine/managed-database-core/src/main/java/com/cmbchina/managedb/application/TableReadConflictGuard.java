package com.cmbchina.managedb.application;

import com.cmb.bee.commons.exceptions.BizException;
import com.cmbchina.managedb.domain.DatabaseSchemaPort;
import com.cmbchina.managedb.domain.DatasourceResource;
import com.cmbchina.managedb.domain.ReadOperationSnapshot;
import com.cmbchina.managedb.domain.ResourceErrorCode;
import com.cmbchina.managedb.domain.TableOperation;
import com.cmbchina.managedb.domain.repository.TableOperationRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 以操作受理记录核对物理读取期间可能发生的受管 DDL。
 */
@Component
@RequiredArgsConstructor
public class TableReadConflictGuard {
    private final TableOperationRepository repository;
    private final DatabaseSchemaPort schemas;

    Check before(String datasourceKey) {
        ReadOperationSnapshot snapshot = repository.readOperationSnapshot(datasourceKey, null);
        snapshot.getResource().requireActive();
        Check check = new Check(snapshot);
        check.global = !datasourceKey.equals(check.resource().getDatasourceKey().getValue())
                || !validMarker(snapshot) || !collectActive(check);
        return check;
    }

    Check after(Check before) {
        ReadOperationSnapshot previous = before.snapshot;
        String datasourceKey = before.resource().getDatasourceKey().getValue();
        ReadOperationSnapshot snapshot = repository.readOperationSnapshot(datasourceKey, previous.getMaxId());
        snapshot.getResource().requireActive();
        Check check = new Check(snapshot);
        check.affected.addAll(before.affected);
        long delta = snapshot.getOperationCount() - previous.getOperationCount();
        if (before.global || !datasourceKey.equals(check.resource().getDatasourceKey().getValue())
                || before.resource().getDatasourceId() != check.resource().getDatasourceId()
                || !validMarker(snapshot) || delta != snapshot.getOperationsSinceMarker().size()
                || !collectActive(check)) {
            check.global = true;
            return check;
        }

        long lastId = previous.getMaxId();
        Long activeId = check.resource().getActiveOperationId();
        boolean activeAccountedFor = activeId == null || Objects.equals(activeId, before.resource().getActiveOperationId());
        for (TableOperation operation : snapshot.getOperationsSinceMarker()) {
            if (operation == null || operation.getId() <= lastId || !collect(check, operation)
                    || (isOccupied(operation) && !Objects.equals(activeId, operation.getId()))) {
                check.global = true;
                return check;
            }
            lastId = operation.getId();
            activeAccountedFor |= Objects.equals(activeId, lastId);
        }
        check.global = !activeAccountedFor || lastId != snapshot.getMaxId();
        return check;
    }

    <T> T readTable(Check before, String name, Supplier<T> read) {
        Set<String> requested = Collections.singleton(name);
        before.requireClear(requested);
        try {
            return read.get();
        } finally {
            // 同表 DDL 可能使绑定或物理查询暂时返回缺表，仍以冲突合同结束。
            after(before).requireClear(requested);
        }
    }

    private static boolean validMarker(ReadOperationSnapshot snapshot) {
        return snapshot.getOperationCount() == 0 ? snapshot.getMaxId() == 0
                : snapshot.getOperationCount() > 0 && snapshot.getMaxId() > 0;
    }

    private boolean collectActive(Check check) {
        TableOperation operation = check.snapshot.getActiveOperation();
        Long id = check.resource().getActiveOperationId();
        return id == null ? operation == null
                : operation != null && id == operation.getId() && id > 0 && id <= check.snapshot.getMaxId()
                        && isOccupied(operation) && collect(check, operation);
    }

    private static boolean isOccupied(TableOperation operation) {
        return operation.getStatus() == TableOperation.Status.RUNNING || operation.getStatus() == TableOperation.Status.UNKNOWN;
    }

    private boolean collect(Check check, TableOperation operation) {
        DatasourceResource resource = check.resource();
        if (!resource.getDatasourceKey().getValue().equals(operation.getDatasourceKey())
                || resource.getDatasourceId() != operation.getDatasourceId() || operation.getStatus() == null
                || operation.getMode() == null || operation.getMode() == TableOperation.Mode.RELEASE
                || operation.getTableNames().isEmpty()
                || (operation.getMode() != TableOperation.Mode.CREATE_BATCH && operation.getTableNames().size() != 1)) {
            return false;
        }
        Set<String> targets = new HashSet<>();
        for (String name : operation.getTableNames()) {
            if (name == null || name.trim().isEmpty()) {
                return false;
            }
            String key;
            try {
                key = schemas.tableKey(resource.getDatasourceId(), name);
            } catch (BizException e) {
                if (e.getErrorCode() != ResourceErrorCode.INVALID_ARGUMENT
                        && e.getErrorCode() != ResourceErrorCode.SQL_UNSUPPORTED) {
                    throw e;
                }
                return false;
            }
            if (key == null || !targets.add(key)) {
                return false;
            }
        }
        check.affected.addAll(targets);
        return true;
    }

    @Getter
    @RequiredArgsConstructor
    static final class Check {
        private final ReadOperationSnapshot snapshot;
        private final Set<String> affected = new HashSet<>();
        private boolean global;

        DatasourceResource resource() {
            return snapshot.getResource();
        }

        boolean conflicts(Collection<String> names) {
            return global || !Collections.disjoint(affected, names);
        }

        void requireClear(Collection<String> names) {
            if (conflicts(names)) {
                throw new BizException(ResourceErrorCode.RESOURCE_BUSY);
            }
        }
    }
}
