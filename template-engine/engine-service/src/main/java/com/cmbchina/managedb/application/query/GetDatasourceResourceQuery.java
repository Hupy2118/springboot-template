package com.cmbchina.managedb.application.query;

import com.cmbchina.managedb.domain.valueobject.DatasourceKey;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * 按已有 datasourceKey 查询，不引入尚未接入的用户身份。
 */
@Getter
@RequiredArgsConstructor
public final class GetDatasourceResourceQuery {
    @NonNull
    private final DatasourceKey datasourceKey;
}
