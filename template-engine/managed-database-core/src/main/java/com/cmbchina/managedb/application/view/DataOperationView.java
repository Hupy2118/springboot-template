package com.cmbchina.managedb.application.view;

import com.cmbchina.managedb.domain.AuthorizationSeedPayload;
import com.cmbchina.managedb.domain.DataOperation;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/** 公开查询只返回安全摘要，操作 ID 使用十进制字符串。 */
@Getter
public final class DataOperationView {
    private final String operationId;
    private final String purpose = AuthorizationSeedPayload.PURPOSE;
    private final DataOperation.Status status;
    private final String datasourceKey;
    private final String requestFingerprint;
    private final List<String> tableNames;
    private final int createdCount;
    private final int existingCount;
    private final boolean verified;
    private final String errorCode;

    /** 仅从管理库安全投影生成对外结果。 */
    public DataOperationView(DataOperation operation) {
        DataOperation.Result result = operation.getResult();
        operationId = Long.toString(operation.getId());
        status = result.getStatus();
        datasourceKey = operation.getDatasourceKey();
        requestFingerprint = "sha256:" + operation.getFingerprint();
        tableNames = Arrays.stream(AuthorizationSeedPayload.Table.values())
                .map(table -> table.physicalName(datasourceKey)).collect(Collectors.toList());
        createdCount = result.getCreatedCount();
        existingCount = result.getExistingCount();
        verified = status == DataOperation.Status.SUCCEEDED;
        errorCode = result.getError() == null ? null : result.getError().name();
    }

    /** 受理仅返回原操作身份和状态。 */
    @Getter
    @RequiredArgsConstructor
    public static final class Receipt {
        private final String operationId;
        private final DataOperation.Status status;
        private final String purpose = AuthorizationSeedPayload.PURPOSE;
    }
}
