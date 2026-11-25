package com.cmbchina.managedb.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 同一管理库只读事务内取得的资源、占用和保留操作记录。
 */
@Getter
@RequiredArgsConstructor
public final class ReadOperationSnapshot {
    private final DatasourceResource resource;
    private final long operationCount;
    private final long maxId;
    private final TableOperation activeOperation;
    private final List<TableOperation> operationsSinceMarker;
}
