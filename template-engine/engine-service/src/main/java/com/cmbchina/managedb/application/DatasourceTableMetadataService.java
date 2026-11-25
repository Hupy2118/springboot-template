package com.cmbchina.managedb.application;

import com.cmb.bee.commons.exceptions.BizException;
import com.cmbchina.managedb.adapter.web.dto.DatasourceTablesView;
import com.cmbchina.managedb.adapter.web.dto.TableColumnsView;
import com.cmbchina.managedb.domain.DatabaseSchemaPort;
import com.cmbchina.managedb.domain.DatasourceResource;
import com.cmbchina.managedb.domain.ResourceErrorCode;
import com.cmbchina.managedb.domain.TableBinding;
import com.cmbchina.managedb.domain.TableMetadata;
import com.cmbchina.managedb.domain.repository.TableOperationRepository;
import com.cmbchina.managedb.domain.valueobject.DatasourceKey;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 按有效资源及精确表归属读取实际元数据，不按前缀扫描共享 Schema。
 */
@Service
@RequiredArgsConstructor
public class DatasourceTableMetadataService {
    private final TableOperationRepository repository;
    private final DatabaseSchemaPort schemas;
    private final TableReadConflictGuard guard;

    /**
     * 只返回已确认建成且真实存在的应用表；数据库读取失败不能伪装为空清单。
     */
    public DatasourceTablesView tables(String rawDatasourceKey) {
        String datasourceKey = new DatasourceKey(rawDatasourceKey).getValue();
        TableReadConflictGuard.Check before = guard.before(datasourceKey);
        before.requireClear(Collections.emptySet());
        DatasourceResource resource = before.resource();
        List<TableMetadata> result = new ArrayList<>();
        for (TableBinding binding : repository.bindings(datasourceKey)) {
            if (binding.isDeleted() || binding.getStatus() != TableBinding.Status.CREATED) {
                continue;
            }
            binding.requireOwner(datasourceKey, resource.getDatasourceId());
            String name = schemas.tableKey(resource.getDatasourceId(), binding.getTableName());
            if (before.getAffected().contains(name)) {
                continue;
            }
            Optional<TableMetadata> actual = schemas.readTableMetadata(resource.getDatasourceId(), name);
            if (actual.isPresent()) {
                result.add(actual.get());
            }
        }
        TableReadConflictGuard.Check after = guard.after(before);
        after.requireClear(Collections.emptySet());
        result.removeIf(table -> after.getAffected().contains(table.getTableName()));
        result.sort(Comparator.comparing(TableMetadata::getTableName));
        return new DatasourceTablesView(datasourceKey, result);
    }

    /**
     * 查字段前先校验归属与 CREATED，目录外表和其他数据源资源表均不能读取。
     */
    public TableColumnsView columns(String rawDatasourceKey, String rawTableName) {
        String datasourceKey = new DatasourceKey(rawDatasourceKey).getValue();
        TableReadConflictGuard.Check before = guard.before(datasourceKey);
        before.requireClear(Collections.emptySet());
        DatasourceResource resource = before.resource();
        String name = schemas.tableKey(resource.getDatasourceId(), rawTableName);
        return guard.readTable(before, name, () -> {
            requireReadableBinding(resource, name);
            TableMetadata actual = schemas.readTableMetadata(resource.getDatasourceId(), name)
                    .orElseThrow(() -> new BizException(ResourceErrorCode.RESOURCE_NOT_FOUND));
            requireReadableBinding(resource, name);
            return new TableColumnsView(datasourceKey, actual);
        });
    }

    /**
     * 使用管理目录判断精确归属，不用相同前缀或真实存在代替授权。
     */
    private void requireReadableBinding(DatasourceResource resource, String name) {
        TableBinding binding =
                repository.binding(resource.getDatasourceId(), name).orElseThrow(() -> new BizException(ResourceErrorCode.RESOURCE_NOT_FOUND));
        binding.requireOwner(resource.getDatasourceKey().getValue(), resource.getDatasourceId());
        if (binding.getStatus() != TableBinding.Status.CREATED) {
            throw new BizException(ResourceErrorCode.RESOURCE_NOT_FOUND);
        }
    }
}
