package com.cmbchina.managedb.infrastructure.persistence;

import com.cmb.bee.commons.exceptions.BizException;
import com.cmbchina.managedb.domain.Actor;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.cmbchina.managedb.domain.ResourceErrorCode;
import com.cmbchina.managedb.domain.TableBinding;
import com.cmbchina.managedb.domain.TableOperation;
import com.cmbchina.managedb.infrastructure.persistence.mapper.DatasourceRegistrationMapper;
import com.cmbchina.managedb.infrastructure.persistence.mapper.TableBindingMapper;
import com.cmbchina.managedb.infrastructure.persistence.mapper.TableOperationMapper;
import com.cmbchina.managedb.infrastructure.persistence.po.DatasourceRegistrationPo;
import com.cmbchina.managedb.infrastructure.persistence.po.TableBindingPo;
import com.cmbchina.managedb.infrastructure.persistence.po.TableOperationPo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证三表受控事务、条件写及未知占用，测试不创建数据库连接。
 */
class MybatisTableOperationRepositoryTest {
    private static final String APP = "b0ddb23acc91";
    private DatasourceRegistrationMapper resources;
    private TableBindingMapper tables;
    private TableOperationMapper operations;
    private PlatformTransactionManager transactions;
    private MybatisTableOperationRepository repository;
    private TableOperationCodec codec;
    private TableOperation op;
    private TableOperationPo stored;
    private DatasourceRegistrationPo application;

    /**
     * 初始化 Plus 字段元数据和真实 TransactionTemplate 所需替身。
     */
    @BeforeEach
    void setUp() {
        initialize(DatasourceRegistrationPo.class, DatasourceRegistrationMapper.class);
        initialize(TableBindingPo.class, TableBindingMapper.class);
        initialize(TableOperationPo.class, TableOperationMapper.class);
        resources = mock(DatasourceRegistrationMapper.class);
        tables = mock(TableBindingMapper.class);
        operations = mock(TableOperationMapper.class);
        transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        IdentifierGenerator ids = mock(IdentifierGenerator.class);
        when(ids.nextId(any())).thenReturn(102L);
        codec = new TableOperationCodec(new ObjectMapper());
        repository = new MybatisTableOperationRepository(resources, tables, operations, ids, codec, transactions, 10);
        application = new DatasourceRegistrationPo();
        application.setDatasourceKey(APP);
        application.setDatasourceId(1L);
        application.setStatus("REGISTERED");
        application.setDeleted(0);
        when(resources.lock(APP)).thenReturn(application);
        when(tables.insert(any())).thenReturn(1);
        when(tables.updateStatusById(anyLong(), anyString())).thenReturn(1);
        when(operations.insert(any())).thenReturn(1);
        when(operations.updateResultByIdAndStatus(anyLong(), anyString(), anyString())).thenReturn(1);
        when(operations.updateStatusAndResultByIdAndStatus(anyLong(), anyString(), anyString(), anyString())).thenReturn(1);
        when(resources.claim(APP, 101L)).thenReturn(1);
        when(resources.release(APP, 101L)).thenReturn(1);
        op = TableOperation.start(101L, APP, 1L, "orders", "CREATE TABLE orders(id bigint)", TableOperation.Mode.CREATE, false, Actor.system());
        stored = new TableOperationPo();
        stored.setId(101L);
        stored.setDatasourceKey(APP);
        stored.setDatasourceId(1L);
        stored.setActionType("CREATE_TABLE");
        stored.setStatus("RUNNING");
        stored.setCreatedAt(op.getCreatedAt());
        stored.setExecutionJson(codec.content(op));
        stored.setExecutionResult(codec.result(op));
    }

    /**
     * 只注册映射信息，不启动 MyBatis 会话。
     */
    private static void initialize(Class<?> po, Class<?> mapper) {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        assistant.setCurrentNamespace(mapper.getName());
        TableInfoHelper.initTableInfo(assistant, po);
    }

    /**
     * 设置同一应用及目录占用，执行步骤写入前使用固定锁顺序。
     */
    private void occupied() {
        application.setActiveOperationId(101L);
        TableBindingPo table = new TableBindingPo();
        table.setId(102L);
        table.setDatasourceKey(APP);
        table.setDatasourceId(1L);
        table.setTableName("orders");
        table.setStatus("REGISTERED");
        table.setDeleted(0);
        when(tables.lock(1L, "orders")).thenReturn(table);
        when(operations.lock(APP, 101L)).thenReturn(stored);
    }

    /**
     * 新目录、操作及应用认领只能一次提交。
     */
    @Test
    void acceptsThreeRecordsInOneTransaction() {
        repository.begin(op, null);
        org.mockito.InOrder order = inOrder(resources, tables, operations, transactions);
        order.verify(resources).lock(APP);
        order.verify(tables).lock(1L, "orders");
        order.verify(tables).insert(any());
        order.verify(operations).insert(any());
        order.verify(resources).claim(APP, 101L);
        order.verify(transactions).commit(any());
        verify(transactions, times(1)).getTransaction(any());
    }

    /**
     * 位置唯一键冲突时全事务回滚，不能插入操作或认领应用。
     */
    @Test
    void duplicateLocationRollsBackAcceptance() {
        when(tables.insert(any())).thenThrow(new DuplicateKeyException("位置冲突"));
        assertSame(ResourceErrorCode.TABLE_BINDING_CONFLICT, assertThrows(BizException.class,
                () -> repository.begin(op, null)).getErrorCode());
        verify(transactions).rollback(any());
        verify(transactions, never()).commit(any());
        verify(operations, never()).insert(any());
        verify(resources, never()).claim(anyString(), anyLong());
    }

    /**
     * 认领条件未命中也要回滚先前的目录和操作写入。
     */
    @Test
    void failedClaimRollsBackAllWrites() {
        when(resources.claim(APP, 101L)).thenReturn(0);
        assertThrows(BizException.class, () -> repository.begin(op, null));
        verify(transactions).rollback(any());
        verify(transactions, never()).commit(any());
    }

    /**
     * SQL 前保存同一步 RUNNING 不能二次成功，防止重复发送。
     */
    @Test
    void rejectsDuplicateRunningStepWrite() {
        occupied();
        op.markRunning(0);
        stored.setExecutionResult(codec.result(op));
        assertThrows(BizException.class, () -> repository.saveStep(op, null));
        verify(operations, never()).updateResultByIdAndStatus(anyLong(), anyString(), anyString());
        verify(transactions).rollback(any());
    }

    /**
     * 步骤成功与目录 CREATED 必须在同一次事务中保存。
     */
    @Test
    void commitsStepAndPhysicalStateTogether() {
        occupied();
        op.markRunning(0);
        stored.setExecutionResult(codec.result(op));
        op.complete(0, TableOperation.StepStatus.SUCCEEDED, null, Collections.singletonMap("sqlExecuted", true));
        repository.saveStep(op, TableBinding.Status.CREATED);
        verify(tables).updateStatusById(eq(102L), eq("CREATED"));
        verify(operations).updateResultByIdAndStatus(eq(101L), eq("RUNNING"), anyString());
        verify(transactions).commit(any());
    }

    /**
     * UNKNOWN 结束只写记录，不释放资源占用。
     */
    @Test
    void unknownNeverReleasesApplication() {
        occupied();
        op.complete(0, TableOperation.StepStatus.UNKNOWN, "EXECUTION_UNKNOWN", Collections.emptyMap());
        op.finish(TableOperation.Status.UNKNOWN, "EXECUTION_UNKNOWN");
        repository.finish(op);
        verify(resources, never()).release(anyString(), anyLong());
        verify(transactions).commit(any());
    }

    /**
     * 提交结果不确定只报脱敏系统错误，不被识别为可重放的位置冲突。
     */
    @Test
    void uncertainCommitIsNotConflictOrRetry() {
        doThrow(new TransactionSystemException("提交结果未知")).when(transactions).commit(any());
        assertSame(ResourceErrorCode.INTERNAL_ERROR, assertThrows(BizException.class,
                () -> repository.begin(op, null)).getErrorCode());
        verify(operations, times(1)).insert(any());
    }

    /**
     * 放弃先提交时拒绝新步骤准入，且不释放占用。
     */
    @Test
    void abandonRequestBlocksStaleWorkerAdmission() {
        occupied();
        TableOperation request = codec.restore(stored);
        request.requestAbandon("停止此次建表", Actor.system());
        stored.setExecutionResult(codec.result(request));
        op.markRunning(0);
        assertSame(ResourceErrorCode.OPERATION_ABANDONED, assertThrows(BizException.class,
                () -> repository.saveStep(op, null)).getErrorCode());
        verify(operations, never()).updateResultByIdAndStatus(anyLong(), anyString(), anyString());
        verify(resources, never()).release(anyString(), anyLong());
    }

    /**
     * 已准入的步骤仍可保存结果，旧线程快照不得覆盖后来提交的放弃审计。
     */
    @Test
    void admittedStepKeepsConcurrentAbandonRequest() {
        occupied();
        op.markRunning(0);
        stored.setExecutionResult(codec.result(op));
        TableOperation request = codec.restore(stored);
        request.requestAbandon("停止后续步骤", Actor.system());
        stored.setExecutionResult(codec.result(request));
        op.complete(0, TableOperation.StepStatus.SUCCEEDED, null, Collections.emptyMap());
        repository.saveStep(op, TableBinding.Status.CREATED);
        ArgumentCaptor<String> result = ArgumentCaptor.forClass(String.class);
        verify(operations).updateResultByIdAndStatus(eq(101L), eq("RUNNING"), result.capture());
        assertTrue(result.getValue().contains("停止后续步骤"));
        assertNotNull(op.getAbandonRequest());
    }

    /**
     * UNKNOWN 申请只追加审计，不伪造确定结果或释放占用；重复申请保留首次原因。
     */
    @Test
    void unknownAbandonRequestIsDurableAndIdempotent() {
        occupied();
        op.complete(0, TableOperation.StepStatus.UNKNOWN, "EXECUTION_UNKNOWN", Collections.emptyMap());
        op.finish(TableOperation.Status.UNKNOWN, "EXECUTION_UNKNOWN");
        stored.setStatus("UNKNOWN");
        stored.setExecutionResult(codec.result(op));
        TableOperation requested = repository.requestAbandon(APP, 101L, "接受已发生的变更", Actor.system());
        assertEquals("MANUAL_REQUIRED", requested.recoveryState());
        assertNull(requested.getFinishedAt());
        stored.setExecutionResult(codec.result(requested));
        TableOperation repeated = repository.requestAbandon(APP, 101L, "第二次原因", Actor.system());
        assertEquals("接受已发生的变更", repeated.getAbandonRequest().getReason());
        verify(operations, times(1)).updateResultByIdAndStatus(anyLong(), anyString(), anyString());
        verify(resources, never()).release(anyString(), anyLong());
    }

    /**
     * 原操作已结束时重复申请不影响当前新操作占用。
     */
    @Test
    void terminalRequestNeverReleasesNewOperation() {
        occupied();
        application.setActiveOperationId(999L);
        stored.setStatus("FAILED");
        assertEquals(TableOperation.Status.FAILED, repository.requestAbandon(APP, 101L, "重复请求", Actor.system()).getStatus());
        verify(operations, never()).updateResultByIdAndStatus(anyLong(), anyString(), anyString());
        verify(resources, never()).release(anyString(), anyLong());
    }

    /**
     * 人工结束后的审计、未知步骤及原时间必须可被详情重新读取。
     */
    @Test
    void restoresManualResolutionWithoutRewritingExecutionEvidence() throws Exception {
        op.complete(0, TableOperation.StepStatus.UNKNOWN, "EXECUTION_UNKNOWN", Collections.emptyMap());
        op.finish(TableOperation.Status.UNKNOWN, "EXECUTION_UNKNOWN");
        com.fasterxml.jackson.databind.node.ObjectNode result = (com.fasterxml.jackson.databind.node.ObjectNode)
                new ObjectMapper().readTree(codec.result(op));
        result.put("errorCode", "MANUALLY_ABANDONED");
        result.putObject("manualResolution").put("resolvedBy", "operator").put("previousStatus", "UNKNOWN");
        stored.setStatus("FAILED");
        stored.setExecutionResult(result.toString());
        TableOperation restored = codec.restore(stored);
        assertEquals("MANUALLY_ABANDONED", restored.recoveryState());
        assertEquals(TableOperation.StepStatus.UNKNOWN, restored.getSteps().get(0).getStatus());
        assertNull(restored.getFinishedAt());
        assertTrue(codec.result(restored).contains("manualResolution"));
    }

}
