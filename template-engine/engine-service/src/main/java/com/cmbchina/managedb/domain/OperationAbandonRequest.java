package com.cmbchina.managedb.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Instant;

/**
 * 已持久化的放弃意图；服务身份不冒充 Studio 的最终用户。
 */
@Getter
@AllArgsConstructor
public final class OperationAbandonRequest {
    private final Instant requestedAt;
    private final String requestedBy;
    private final String reason;
}
