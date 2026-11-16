package com.cmbchina.managedb.application;

import com.cmb.bee.commons.exceptions.BizException;
import com.cmbchina.managedb.application.view.DataOperationView;
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
    void neverSchedulesExistingKeyInAnyState() {
        for (DataOperation.Result result : new DataOperation.Result[]{DataOperation.Result.running(),
                DataOperation.Result.unknown(), DataOperation.Result.committed(5, 0),
                DataOperation.Result.failed(ResourceErrorCode.SEED_DATA_CONFLICT)}) {
            DataOperation existing = new DataOperation(12, KEY, 1, payload.getFingerprint(), result);
            when(repository.byKey(KEY, IDEMPOTENCY_KEY)).thenReturn(Optional.of(existing));
            assertEquals(result.getStatus(), service.submit(IDEMPOTENCY_KEY, payload).getStatus());
            DataOperationView.Receipt receipt = service.submit(IDEMPOTENCY_KEY, payload, 99L);
            assertEquals("12", receipt.getOperationId());
            assertEquals(result.getStatus(), receipt.getStatus());
        }
        AuthorizationSeedPayload changed = parse(KEY, json(KEY).replace(SUBJECT, "changed-fixture"));
        assertEquals(ResourceErrorCode.IDEMPOTENCY_CONFLICT, assertThrows(BizException.class,
                () -> service.submit(IDEMPOTENCY_KEY, changed, 99L)).getErrorCode());
        verifyNoInteractions(database, dispatcher);
    }

    @Test
    void dispatchesOnlyTheNewAttemptAndReusesItsReceipt() {
        DataOperation retry = new DataOperation(13, KEY, 1, payload.getFingerprint(), DataOperation.Result.running());
        when(repository.preflight(payload)).thenReturn(1L);
        when(dispatcher.reserve()).thenReturn(slot);
        when(repository.begin(1, RETRY_KEY, payload, 12L)).thenReturn(new DataOperationRepository.Admission(retry, true));
        DataOperation.Result result = DataOperation.Result.committed(5, 0);
        when(database.execute(1, payload)).thenReturn(result);

        assertEquals("13", service.submit(RETRY_KEY, payload, 12L).getOperationId());
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(slot).dispatch(task.capture());
        task.getValue().run();
        verify(repository).requireExecution(retry, payload);
        verify(repository).finish(retry, result);

        when(repository.byKey(KEY, RETRY_KEY)).thenReturn(Optional.of(retry));
        assertEquals("13", service.submit(RETRY_KEY, payload, 12L).getOperationId());
        verify(database).execute(1, payload);
        verify(dispatcher).reserve();
    }

    @Test
    void retryAdmissionRejectionsDoNotDispatch() {
        when(repository.preflight(payload)).thenReturn(1L);
        when(dispatcher.reserve()).thenReturn(slot);
        for (ResourceErrorCode code : new ResourceErrorCode[]{ResourceErrorCode.RESOURCE_NOT_FOUND,
                ResourceErrorCode.RESOURCE_BUSY, ResourceErrorCode.IDEMPOTENCY_CONFLICT}) {
            doThrow(new BizException(code)).when(repository).begin(1, RETRY_KEY, payload, 12L);
            assertEquals(code, assertThrows(BizException.class, () -> service.submit(RETRY_KEY, payload, 12L)).getErrorCode());
        }
        when(dispatcher.reserve()).thenThrow(new BizException(ResourceErrorCode.EXECUTION_CAPACITY_EXHAUSTED));
        assertEquals(ResourceErrorCode.EXECUTION_CAPACITY_EXHAUSTED,
                assertThrows(BizException.class, () -> service.submit(RETRY_KEY, payload, 12L)).getErrorCode());
        verify(slot, never()).dispatch(any());
        verify(database, never()).execute(anyLong(), any());
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
        when(repository.begin(1, IDEMPOTENCY_KEY, payload, null))
                .thenReturn(new DataOperationRepository.Admission(running, created));
    }
}
