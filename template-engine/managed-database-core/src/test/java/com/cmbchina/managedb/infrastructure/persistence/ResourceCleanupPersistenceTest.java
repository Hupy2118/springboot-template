package com.cmbchina.managedb.infrastructure.persistence;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证清理清单序列化、资源锁内复核、逐表目录写入及释放事务，不连接数据库。
 */
class ResourceCleanupPersistenceTest {
    private static final String APP = "12ab34cd56ef";
    private DatasourceRegistrationMapper resources;
    private TableBindingMapper tables;
    private TableOperationMapper operations;
    private PlatformTransactionManager transactions;
    private MybatisTableOperationRepository repository;
    private TableOperationCodec codec;
    private DatasourceRegistrationPo resource;

    /**
     * 初始化 Plus 元数据及真正事务模板所用的替身。
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
        codec = new TableOperationCodec(new ObjectMapper());
        repository = new MybatisTableOperationRepository(resources, tables, operations, mock(IdentifierGenerator.class), codec, transactions, 10);
        resource = new DatasourceRegistrationPo();
        resource.setDatasourceKey(APP);
        resource.setDatasourceId(2L);
        resource.setStatus("REGISTERED");
        resource.setDeleted(0);
        when(resources.lock(APP)).thenReturn(resource);
        when(resources.claim(APP, 101L)).thenReturn(1);
        when(resources.completeRelease(APP, 101L)).thenReturn(1);
        when(operations.countByDatasourceKey(anyString())).thenReturn(0L);
        when(operations.insert(any())).thenReturn(1);
        when(operations.updateResultByIdAndStatus(anyLong(), anyString(), anyString())).thenReturn(1);
        when(operations.updateStatusAndResultByIdAndStatus(anyLong(), anyString(), anyString(), anyString())).thenReturn(1);
        when(tables.updateStatusById(anyLong(), anyString())).thenReturn(1);
    }

    /**
     * 只装配字段元数据，不创建 MyBatis 会话。
     */
    private static void initialize(Class<?> po, Class<?> mapper) {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        assistant.setCurrentNamespace(mapper.getName());
        TableInfoHelper.initTableInfo(assistant, po);
    }

    /**
     * 构造完整已登记位置，所有清理写入都要重新核对归属。
     */
    private TableBindingPo binding(String name, long id, String status) {
        TableBindingPo row = new TableBindingPo();
        row.setId(id);
        row.setDatasourceKey(APP);
        row.setDatasourceId(2L);
        row.setTableName(name);
        row.setDeleted(0);
        row.setStatus(status);
        when(tables.lock(2L, name)).thenReturn(row);
        return row;
    }

    /**
     * 保存操作替身，Codec 与运行时查询使用同一当前存储格式。
     */
    private TableOperationPo stored(TableOperation op) {
        TableOperationPo row = new TableOperationPo();
        row.setId(op.getId());
        row.setDatasourceKey(APP);
        row.setDatasourceId(2L);
        row.setStatus("RUNNING");
        row.setActionType(TableOperationCodec.actionType(op.getMode()));
        row.setCreatedAt(op.getCreatedAt());
        row.setExecutionJson(codec.content(op));
        row.setExecutionResult(codec.result(op));
        when(operations.lock(APP, 101L)).thenReturn(row);
        return row;
    }

    /**
     * 不新增管理表，两个清理模式及空应用都能恢复固定目标。
     */
    @Test
    void roundTripsCleanupAndEmptyApplication() {
        for (TableOperation op : Arrays.asList(TableOperation.cleanup(101L, APP, 2L, TableOperation.Mode.DROP, Collections.singletonList("orders"), Actor.system()),
                TableOperation.cleanup(101L, APP, 2L, TableOperation.Mode.RELEASE, Arrays.asList("child", "parent"), Actor.system()),
                TableOperation.cleanup(101L, APP, 2L, TableOperation.Mode.RELEASE, Collections.emptyList(), Actor.system()))) {
            TableOperation restored = codec.restore(stored(op));
            assertEquals(op.getMode(), restored.getMode());
            assertEquals(op.getTableNames(), restored.getTableNames());
            assertEquals(op.getSteps().size(), restored.getSteps().size());
        }
    }

    /**
     * 未知存储动作不能被默认分支解释成 REBUILD。
     */
    @Test
    void rejectsUnknownStoredAction() {
        TableOperationPo row = stored(TableOperation.cleanup(101L, APP, 2L, TableOperation.Mode.RELEASE, Collections.emptyList(), Actor.system()));
        row.setActionType("UNRECOGNIZED");
        assertThrows(BizException.class, () -> codec.restore(row));
    }

    /**
     * 资源锁内计数变化拒绝受理，不创建操作或认领占用。
     */
    @Test
    void rejectsConcurrentChangeBeforeAcceptance() {
        when(operations.countByDatasourceKey(APP)).thenReturn(1L);
        assertSame(ResourceErrorCode.CLEANUP_PREVIEW_CHANGED, assertThrows(BizException.class,
                () -> repository.beginCleanup(TableOperation.cleanup(101L, APP, 2L, TableOperation.Mode.RELEASE, Collections.emptyList(), Actor.system()),
                        0L)).getErrorCode());
        verify(operations, never()).insert(any());
        verify(resources, never()).claim(anyString(), anyLong());
        verify(transactions).rollback(any());
    }

    /**
     * 完整清单在锁内再次枚举，确认之后新增的表不能遗漏。
     */
    @Test
    void rejectsChangedApplicationManifest() {
        TableBindingPo row = binding("new_table", 10L, "CREATED");
        when(tables.selectByDatasourceKey(APP)).thenReturn(Collections.singletonList(row));
        assertThrows(BizException.class,
                () -> repository.beginCleanup(TableOperation.cleanup(101L, APP, 2L, TableOperation.Mode.RELEASE, Collections.emptyList(), Actor.system()), 0L));
        verify(operations, never()).insert(any());
    }

    /**
     * 清理不能使用 CREATE 的目录建立逻辑，也不能认领目录外表。
     */
    @Test
    void cleanupNeverCreatesBindings() {
        assertThrows(BizException.class,
                () -> repository.beginCleanup(TableOperation.cleanup(101L, APP, 2L, TableOperation.Mode.DROP, Collections.singletonList("missing"), Actor.system()),
                        0L));
        verify(tables, never()).insert(any());
        verify(operations, never()).insert(any());
    }

    /**
     * 多表第二步只能更新第二张表的目录，不能沿用单表逻辑误写第一张表。
     */
    @Test
    void updatesExactStepTable() {
        TableOperation op = TableOperation.cleanup(101L, APP, 2L, TableOperation.Mode.RELEASE, Arrays.asList("a", "b"), Actor.system());
        resource.setActiveOperationId(101L);
        binding("a", 10L, "DROPPED");
        binding("b", 11L, "CREATED");
        op.markRunning(0);
        op.complete(0, TableOperation.StepStatus.SUCCEEDED, null, Collections.emptyMap());
        op.markRunning(1);
        stored(op);
        op.complete(1, TableOperation.StepStatus.SUCCEEDED, null, Collections.emptyMap());
        repository.saveStep(op, TableBinding.Status.DROPPED);
        verify(tables).updateStatusById(eq(11L), eq("DROPPED"));
    }

    /**
     * 最终完成事实及 RELEASED 写入同一事务，不能先结束注册再清表。
     */
    @Test
    void atomicallyReleasesAfterPersistedSuccess() {
        TableOperation op = TableOperation.cleanup(101L, APP, 2L, TableOperation.Mode.RELEASE, Collections.singletonList("orders"), Actor.system());
        resource.setActiveOperationId(101L);
        binding("orders", 10L, "DROPPED");
        op.markRunning(0);
        op.complete(0, TableOperation.StepStatus.SUCCEEDED, null, Collections.emptyMap());
        stored(op);
        op.finish(TableOperation.Status.SUCCEEDED, null);
        repository.finish(op);
        org.mockito.InOrder order = inOrder(operations, resources, transactions);
        order.verify(operations).lock(APP, 101L);
        order.verify(operations).updateStatusAndResultByIdAndStatus(anyLong(), anyString(), anyString(), anyString());
        order.verify(resources).completeRelease(APP, 101L);
        order.verify(transactions).commit(any());
        verify(resources, never()).release(anyString(), anyLong());
    }

    /**
     * 目录仍指向建成表时事务回滚，不能仅凭内存步骤标记结束资源。
     */
    @Test
    void refusesReleaseWithoutDroppedDirectory() {
        TableOperation op = TableOperation.cleanup(101L, APP, 2L, TableOperation.Mode.RELEASE, Collections.singletonList("orders"), Actor.system());
        resource.setActiveOperationId(101L);
        binding("orders", 10L, "CREATED");
        op.markRunning(0);
        op.complete(0, TableOperation.StepStatus.SUCCEEDED, null, Collections.emptyMap());
        stored(op);
        op.finish(TableOperation.Status.SUCCEEDED, null);
        assertThrows(BizException.class, () -> repository.finish(op));
        verify(resources, never()).completeRelease(anyString(), anyLong());
        verify(transactions).rollback(any());
    }
}
