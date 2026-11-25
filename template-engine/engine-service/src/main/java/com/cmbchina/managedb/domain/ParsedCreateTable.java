package com.cmbchina.managedb.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 已解析的原始 SQL 与内存期望结构。
 */
@Getter
@RequiredArgsConstructor
public final class ParsedCreateTable {
    private final String sql;
    private final TableStructure structure;
}
