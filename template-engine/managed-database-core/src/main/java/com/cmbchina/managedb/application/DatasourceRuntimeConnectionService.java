package com.cmbchina.managedb.application;

import com.cmb.bee.commons.exceptions.BizException;
import com.cmbchina.managedb.application.view.RuntimeConnectionView;
import com.cmbchina.managedb.domain.CheckContext;
import com.cmbchina.managedb.domain.DatabaseSchemaPort;
import com.cmbchina.managedb.domain.ResourceErrorCode;
import com.cmbchina.managedb.domain.RuntimeConnectionPort;
import com.cmbchina.managedb.domain.RuntimeDatabaseConnection;
import com.cmbchina.managedb.domain.repository.TableOperationRepository;
import com.cmbchina.managedb.domain.valueobject.DatasourceKey;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 在有效资源的固定挂载上取得调试连接，不授予用户成员权限。
 */
@Service
@RequiredArgsConstructor
public class DatasourceRuntimeConnectionService {
    private final TableOperationRepository repository;
    private final DatabaseSchemaPort schemas;
    private final RuntimeConnectionPort connections;

    /**
     * 前后核对资源与操作快照，忙碌、释放或配置缺失时不返回凭据。
     */
    public RuntimeConnectionView resolve(String rawDatasourceKey) {
        String datasourceKey = new DatasourceKey(rawDatasourceKey).getValue();
        CheckContext before = available(datasourceKey);
        long datasourceId = before.getResource().getDatasourceId();
        schemas.verifyAccessible(datasourceId);
        RuntimeDatabaseConnection connection = connections.resolve(datasourceId);
        CheckContext after = available(datasourceKey);
        if (datasourceId != after.getResource().getDatasourceId() || before.getOperationCount() != after.getOperationCount()) {
            throw new BizException(ResourceErrorCode.RESOURCE_BUSY);
        }
        return new RuntimeConnectionView(datasourceKey, datasourceId, connection);
    }

    /**
     * 资源必须有效且不存在尚未完成的表操作。
     */
    private CheckContext available(String datasourceKey) {
        CheckContext context = repository.checkContext(datasourceKey);
        context.getResource().requireActive();
        if (context.getResource().getActiveOperationId() != null) {
            throw new BizException(ResourceErrorCode.RESOURCE_BUSY);
        }
        return context;
    }
}
