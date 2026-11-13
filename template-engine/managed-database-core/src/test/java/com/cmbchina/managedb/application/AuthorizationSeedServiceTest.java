package com.cmbchina.managedb.application;

import com.cmbchina.managedb.domain.*;
import com.cmbchina.managedb.domain.repository.DataOperationRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static com.cmbchina.managedb.AuthorizationSeedFixture.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证应用编排的调度边界和故障后的重试行为。 */
class AuthorizationSeedServiceTest {
    private final DataOperationRepository repository = mock(DataOperationRepository.class);
    private final AuthorizationSeedPort database = mock(AuthorizationSeedPort.class);
    private final TableTaskDispatcher dispatcher = mock(TableTaskDispatcher.class);
    private final TableTaskDispatcher.Reservation slot = mock(TableTaskDispatcher.Reservation.class);
    private final AuthorizationSeedService service = new AuthorizationSeedService(repository, database, dispatcher);
    private final AuthorizationSeedPayload payload = parse(KEY, json(KEY));
    private final DataOperation running = new DataOperation(12, KEY, 1, payload.getFingerprint(), DataOperation.Result.running());

    /** 已有记录绕过预检与执行，进程重启后仍不会调度。 */
    @Test
    void neverSchedulesExistingRunningOrUnknown() {
        DataOperation unknown = new DataOperation(12, KEY, 1, payload.getFingerprint(), DataOperation.Result.unknown());
        when(repository.byKey(KEY, IDEMPOTENCY_KEY)).thenReturn(Optional.of(running), Optional.of(unknown));
        assertEquals("12", service.submit(IDEMPOTENCY_KEY, payload).getOperationId());
        assertEquals(DataOperation.Status.UNKNOWN, service.submit(IDEMPOTENCY_KEY, payload).getStatus());
        verifyNoInteractions(database, dispatcher);
    }

    /** 并发赢家已经受理时，其他请求返回同一记录且不提交任务。 */
    @Test
    void concurrentAdmissionLoserDoesNotDispatch() {
        arrangeAdmission(false);
        assertEquals("12", service.submit(IDEMPOTENCY_KEY, payload).getOperationId());
        verify(slot, never()).dispatch(any());
        verify(database, never()).execute(anyLong(), any());
    }

    /** 目录冲突或 DDL 持有占用时不访问业务库。 */
    @Test
    void busyOrUnownedDoesNotPreflightTarget() {
        for (ResourceErrorCode code : new ResourceErrorCode[]{ResourceErrorCode.RESOURCE_BUSY, ResourceErrorCode.TABLE_BINDING_CONFLICT}) {
            doThrow(new BizException(code)).when(repository).preflight(payload);
            assertEquals(code, assertThrows(BizException.class, () -> service.submit(IDEMPOTENCY_KEY, payload)).getErrorCode());
        }
        verifyNoInteractions(database, dispatcher);
    }

    /** 目标事务成功后即使回写失败也只执行一次，超时重试只查原操作。 */
    @Test
    void committedResultWithManagementFailureDoesNotReplay() {
        arrangeAdmission(true);
        DataOperation.Result committed = DataOperation.Result.committed(5, 0);
        when(database.execute(1, payload)).thenReturn(committed);
        doThrow(new BizException(ResourceErrorCode.INTERNAL_ERROR)).when(repository).finish(running, committed);
        assertEquals(DataOperation.Status.RUNNING, service.submit(IDEMPOTENCY_KEY, payload).getStatus());
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(slot).dispatch(task.capture());
        task.getValue().run();
        when(repository.byKey(KEY, IDEMPOTENCY_KEY)).thenReturn(Optional.of(running));
        assertEquals("12", service.submit(IDEMPOTENCY_KEY, payload).getOperationId());
        verify(database).execute(1, payload);
        verify(repository).finish(running, committed);
        verify(dispatcher).reserve();
    }

    /** 不明的目标事务结果传给仓储保留占用。 */
    @Test
    void forwardsUnknownOutcome() {
        arrangeAdmission(true);
        DataOperation.Result unknown = DataOperation.Result.unknown();
        when(database.execute(1, payload)).thenReturn(unknown);
        service.submit(IDEMPOTENCY_KEY, payload);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(slot).dispatch(task.capture());
        task.getValue().run();
        verify(repository).finish(running, unknown);
    }

    /** 调度明确拒绝时持久化失败，执行器没有收到工作任务。 */
    @Test
    void recordsDefiniteDispatchRejection() {
        arrangeAdmission(true);
        doThrow(new BizException(ResourceErrorCode.EXECUTION_CAPACITY_EXHAUSTED)).when(slot).dispatch(any());
        DataOperation failed = new DataOperation(12, KEY, 1, payload.getFingerprint(),
                DataOperation.Result.failed(ResourceErrorCode.EXECUTION_CAPACITY_EXHAUSTED));
        when(repository.detail(KEY, 12)).thenReturn(Optional.of(failed));
        assertEquals(DataOperation.Status.FAILED, service.submit(IDEMPOTENCY_KEY, payload).getStatus());
        ArgumentCaptor<DataOperation.Result> result = ArgumentCaptor.forClass(DataOperation.Result.class);
        verify(repository).finish(eq(running), result.capture());
        assertEquals(DataOperation.Status.FAILED, result.getValue().getStatus());
        verify(database, never()).execute(anyLong(), any());
    }

    /** 模拟管理事务返回已提交的新记录或并发赢家的原记录。 */
    private void arrangeAdmission(boolean created) {
        when(repository.preflight(payload)).thenReturn(1L);
        when(dispatcher.reserve()).thenReturn(slot);
        when(repository.begin(1, IDEMPOTENCY_KEY, payload)).thenReturn(new DataOperationRepository.Admission(running, created));
    }
}
