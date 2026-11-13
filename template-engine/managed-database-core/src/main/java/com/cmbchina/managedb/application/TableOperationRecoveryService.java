package com.cmbchina.managedb.application;

import com.cmb.bee.commons.exceptions.BizException;
import com.cmbchina.managedb.application.view.TableOperationView;
import com.cmbchina.managedb.domain.Actor;
import com.cmbchina.managedb.domain.ResourceErrorCode;
import com.cmbchina.managedb.domain.repository.TableOperationRepository;
import com.cmbchina.managedb.domain.valueobject.DatasourceKey;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 用户放弃用例只登记意图，不把用户确认当作数据库执行结束证据。
 */
@Service
@RequiredArgsConstructor
public class TableOperationRecoveryService {
    private final TableOperationRepository repository;

    /**
     * 校验确认与有限原因文本，重复请求返回原处置事实。
     */
    public TableOperationView requestAbandon(String rawDatasourceKey, long operationId, String reason, boolean confirmed, Actor actor) {
        String datasourceKey = new DatasourceKey(rawDatasourceKey).getValue();
        if (operationId <= 0 || !confirmed || reason == null || reason.trim().isEmpty() || reason.length() > 500) {
            throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
        }
        return new TableOperationView(repository.requestAbandon(datasourceKey, operationId, reason, actor));
    }
}
