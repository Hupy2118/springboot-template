package com.cmbchina.managedb.infrastructure.sql;

import com.cmbchina.managedb.domain.ResourceErrorCode;
import com.cmbchina.managedb.domain.TableTaskDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 验证真实容量拒绝的错误分类，不执行 SQL、不等待工作线程。
 */
class BoundedTableTaskDispatcherTest {
    /**
     * 预留耗尽时返回容量码，关闭未转交预留后额度可以重新获取。
     */
    @Test
    void exhaustedReservationsReturnCapacityCode() {
        try (BoundedTableTaskDispatcher dispatcher = new BoundedTableTaskDispatcher(1)) {
            try (TableTaskDispatcher.Reservation reservation = dispatcher.reserve()) {
                assertSame(ResourceErrorCode.EXECUTION_CAPACITY_EXHAUSTED,
                        assertThrows(BizException.class, dispatcher::reserve).getErrorCode());
            }
            try (TableTaskDispatcher.Reservation reservation = dispatcher.reserve()) {
                assertNotNull(reservation);
            }
        }
    }

    /**
     * 线程池停机后的明确拒绝同样属于执行容量不可用，不执行已拒绝任务。
     */
    @Test
    void rejectedDispatchReturnsCapacityCode() {
        try (BoundedTableTaskDispatcher dispatcher = new BoundedTableTaskDispatcher(1);
             TableTaskDispatcher.Reservation reservation = dispatcher.reserve()) {
            dispatcher.close();
            Runnable task = mock(Runnable.class);
            assertSame(ResourceErrorCode.EXECUTION_CAPACITY_EXHAUSTED,
                    assertThrows(BizException.class, () -> reservation.dispatch(task)).getErrorCode());
            verifyNoInteractions(task);
            assertSame(ResourceErrorCode.INVALID_ARGUMENT,
                    assertThrows(BizException.class, () -> reservation.dispatch(task)).getErrorCode());
        }
    }
}
