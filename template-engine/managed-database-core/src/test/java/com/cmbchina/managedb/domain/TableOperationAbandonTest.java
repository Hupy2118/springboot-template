package com.cmbchina.managedb.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 覆盖 requestAbandon 的 requestedBy 取值：有 id 记 id，system 或仅姓名回退 STUDIO_SERVICE，不落 null。
 */
class TableOperationAbandonTest {
    private static TableOperation running() {
        return TableOperation.start(101L, "b0ddb23acc91", 1L, "orders", "CREATE TABLE orders(id bigint)",
                TableOperation.Mode.CREATE, false, Actor.system());
    }

    /**
     * 有用户 id 时 requestedBy 记该 id。
     */
    @Test
    void recordsUserIdWhenPresent() {
        TableOperation op = running();
        op.requestAbandon("停止", Actor.of("123456", "张三"));
        assertEquals("123456", op.getAbandonRequest().getRequestedBy());
    }

    /**
     * system（未登录）回退 STUDIO_SERVICE，保持向后兼容。
     */
    @Test
    void fallsBackToServiceWhenSystem() {
        TableOperation op = running();
        op.requestAbandon("停止", Actor.system());
        assertEquals("STUDIO_SERVICE", op.getAbandonRequest().getRequestedBy());
    }

    /**
     * 只有姓名没有 id 时回退 STUDIO_SERVICE，避免 requestedBy 落 null。
     */
    @Test
    void fallsBackToServiceWhenNameOnly() {
        TableOperation op = running();
        op.requestAbandon("停止", Actor.of(null, "张三"));
        assertEquals("STUDIO_SERVICE", op.getAbandonRequest().getRequestedBy());
    }
}
