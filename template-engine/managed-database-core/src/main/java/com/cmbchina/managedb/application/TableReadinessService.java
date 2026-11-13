package com.cmbchina.managedb.application;

import com.cmb.bee.commons.exceptions.BizException;
import com.cmbchina.managedb.application.view.TableReadinessView;
import com.cmbchina.managedb.domain.CheckContext;
import com.cmbchina.managedb.domain.DatabaseSchemaPort;
import com.cmbchina.managedb.domain.DatasourceResource;
import com.cmbchina.managedb.domain.ResourceErrorCode;
import com.cmbchina.managedb.domain.StructureDifference;
import com.cmbchina.managedb.domain.TableBinding;
import com.cmbchina.managedb.domain.TableStructure;
import com.cmbchina.managedb.domain.TableStructureComparator;
import com.cmbchina.managedb.domain.TableSyncSettings;
import com.cmbchina.managedb.domain.repository.TableOperationRepository;
import com.cmbchina.managedb.domain.valueobject.DatasourceKey;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 启动检查只读，当前全模型由 Studio 提供，历史成功不等于当前同步。
 */
@Service
@RequiredArgsConstructor
public class TableReadinessService {
    private final TableOperationRepository repository;
    private final DatabaseSchemaPort schemas;
    private final TableSqlSupport support;
    private final TableStructureComparator comparator;
    private final TableSyncSettings settings;

    /**
     * 前后使用新快照检测受理写入，不锁管理行等待元数据。
     */
    public TableReadinessView check(String rawDatasourceKey, List<String> sqls) {
        String datasourceKey = new DatasourceKey(rawDatasourceKey).getValue();
        settings.validateList(sqls);
        CheckContext before = repository.checkContext(datasourceKey);
        DatasourceResource resource = before.getResource();
        resource.requireActive();
        if (resource.getActiveOperationId() != null) {
            return busy(before, "OPERATION_IN_PROGRESS");
        }
        List<TableStructure> expected = parseDistinct(sqls, resource);
        List<TableReadinessView.Entry> entries = new ArrayList<>();
        boolean ready = collectEntries(datasourceKey, resource, expected, entries);
        List<String> extra = extraCreatedTables(datasourceKey, expected);
        CheckContext after = repository.checkContext(datasourceKey);
        after.getResource().requireActive();
        if (after.getResource().getActiveOperationId() != null
                || before.getOperationCount() != after.getOperationCount()
                || resource.getDatasourceId() != after.getResource().getDatasourceId()) {
            return busy(after, "CHECK_CHANGED");
        }
        return new TableReadinessView(ready, resource.getDatasourceId(), null, null, null, entries, extra);
    }

    /**
     * 解析全部 SQL 并保证表名不重复。
     */
    private List<TableStructure> parseDistinct(List<String> sqls, DatasourceResource resource) {
        List<TableStructure> expected = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (String sql : sqls) {
            TableStructure structure = support.parse(resource.getDatasourceId(), sql).getStructure();
            if (!names.add(structure.getTableName())) {
                throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
            }
            expected.add(structure);
        }
        return expected;
    }

    /**
     * 逐表评估同步状态，任一非 SYNCED 即整体未就绪。
     */
    private boolean collectEntries(String datasourceKey, DatasourceResource resource,
                                   List<TableStructure> expected, List<TableReadinessView.Entry> entries) {
        boolean ready = true;
        for (TableStructure structure : expected) {
            String name = structure.getTableName();
            String status;
            List<StructureDifference> differences = Collections.emptyList();
            Optional<TableBinding> binding = repository.binding(resource.getDatasourceId(), name);
            if (binding.isPresent()
                    && (binding.get().isDeleted() || !datasourceKey.equals(binding.get().getDatasourceKey()))) {
                status = "TABLE_CONFLICT";
            } else {
                Optional<TableStructure> actual = schemas.readTable(resource.getDatasourceId(), name);
                if (!binding.isPresent()) {
                    status = actual.isPresent() ? "TABLE_CONFLICT" : "NOT_CREATED";
                } else if (binding.get().getStatus() != TableBinding.Status.CREATED || !actual.isPresent()) {
                    status = "NOT_CREATED";
                } else {
                    try {
                        support.requireReferences(datasourceKey, resource.getDatasourceId(), structure);
                        differences = comparator.compare(structure, actual.get());
                        status = differences.isEmpty() ? "SYNCED" : "STRUCTURE_MISMATCH";
                    } catch (BizException e) {
                        if (e.getErrorCode() != ResourceErrorCode.TABLE_BINDING_CONFLICT) {
                            throw e;
                        }
                        status = "TABLE_CONFLICT";
                    }
                }
            }
            if (!status.equals("SYNCED")) {
                ready = false;
            }
            entries.add(new TableReadinessView.Entry(name, status, differences));
        }
        return ready;
    }

    /**
     * 找出已建成但不在本次模型内的额外受管表。
     */
    private List<String> extraCreatedTables(String datasourceKey, List<TableStructure> expected) {
        Set<String> names = new HashSet<>();
        for (TableStructure structure : expected) {
            names.add(structure.getTableName());
        }
        List<String> extra = new ArrayList<>();
        for (TableBinding table : repository.bindings(datasourceKey)) {
            if (table.getStatus() == TableBinding.Status.CREATED && !names.contains(table.getTableName())) {
                extra.add(table.getTableName());
            }
        }
        return extra;
    }

    /**
     * 占用或检查期间出现写入时不伪造逐表成功。
     */
    private TableReadinessView busy(CheckContext context, String reason) {
        Long id = context.getResource().getActiveOperationId();
        String status = id == null ? null
                : repository.detail(context.getResource().getDatasourceKey().getValue(), id)
                .map(op -> op.getStatus().name()).orElse("RUNNING");
        return new TableReadinessView(false, context.getResource().getDatasourceId(), reason,
                id == null ? null : Long.toString(id), status, Collections.emptyList(), Collections.emptyList());
    }
}
