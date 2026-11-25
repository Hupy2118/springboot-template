package com.cmbchina.managedb.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 单次 JDBC 返回的证据，不把网络异常当作明确未执行。
 */
@Getter
@RequiredArgsConstructor
public final class SqlExecutionResult {
    public enum Outcome {NOT_SENT, ACKNOWLEDGED, REJECTED, UNKNOWN}

    private final Outcome outcome;
    private final String errorCode;
}
