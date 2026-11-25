package com.cmbchina.managedb.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 一个短读事务中取得的资源与操作数量，用于检测检查期间的写入。
 */
@Getter
@RequiredArgsConstructor
public final class CheckContext {
    private final DatasourceResource resource;
    private final long operationCount;
}
