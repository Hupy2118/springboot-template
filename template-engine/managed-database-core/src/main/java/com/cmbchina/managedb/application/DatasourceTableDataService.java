package com.cmbchina.managedb.application;

import com.cmb.bee.commons.exceptions.BizException;
import com.cmbchina.managedb.application.view.TableDataView;
import com.cmbchina.managedb.domain.DatabaseSchemaPort;
import com.cmbchina.managedb.domain.DatasourceResource;
import com.cmbchina.managedb.domain.ResourceErrorCode;
import com.cmbchina.managedb.domain.TableBinding;
import com.cmbchina.managedb.domain.TableDataPage;
import com.cmbchina.managedb.domain.TableDataQuery;
import com.cmbchina.managedb.domain.TableDataQueryPort;
import com.cmbchina.managedb.domain.TableMetadata;
import com.cmbchina.managedb.domain.repository.TableOperationRepository;
import com.cmbchina.managedb.domain.valueobject.DatasourceKey;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collections;

/**
 * 数据预览先核对精确归属，读取完成后再确认没有受管资源变更。
 */
@Service
@RequiredArgsConstructor
public class DatasourceTableDataService {
    private final TableOperationRepository repository;
    private final DatabaseSchemaPort schemas;
    private final TableDataQueryPort data;
    private final TableReadConflictGuard guard;

    /**
     * 读取一页当前表数据，条件不支持或资源变化时不能返回伪成功。
     */
    public TableDataView query(String rawDatasourceKey, String rawTableName, TableDataQuery query) {
        if (query == null) {
            throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
        }
        query.requireUnfiltered();
        String datasourceKey = new DatasourceKey(rawDatasourceKey).getValue();
        TableReadConflictGuard.Check before = guard.before(datasourceKey);
        before.requireClear(Collections.emptySet());
        DatasourceResource resource = before.resource();
        String name = schemas.tableKey(resource.getDatasourceId(), rawTableName);
        return guard.readTable(before, name, () -> {
            requireBinding(resource, name);
            TableMetadata table = schemas.readTableMetadata(resource.getDatasourceId(), name)
                    .orElseThrow(() -> new BizException(ResourceErrorCode.RESOURCE_NOT_FOUND));
            if (!name.equals(schemas.tableKey(resource.getDatasourceId(), table.getTableName()))) {
                throw new BizException(ResourceErrorCode.TABLE_BINDING_CONFLICT);
            }
            TableDataPage page = data.read(resource.getDatasourceId(), table, query);
            requireBinding(resource, name);
            return new TableDataView(datasourceKey, table, query, page);
        });
    }

    /**
     * 真实存在或同前缀均不能替代目录归属和 CREATED 事实。
     */
    private void requireBinding(DatasourceResource resource, String name) {
        TableBinding binding =
                repository.binding(resource.getDatasourceId(), name).orElseThrow(() -> new BizException(ResourceErrorCode.RESOURCE_NOT_FOUND));
        binding.requireOwner(resource.getDatasourceKey().getValue(), resource.getDatasourceId());
        if (binding.getStatus() != TableBinding.Status.CREATED) {
            throw new BizException(ResourceErrorCode.RESOURCE_NOT_FOUND);
        }
    }
}
