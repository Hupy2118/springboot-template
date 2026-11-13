package com.cmbchina.managedb.application;

import com.cmb.bee.commons.exceptions.BizException;
import com.cmbchina.managedb.domain.Actor;


import com.cmbchina.managedb.application.view.ResourceCleanupPreview;
import com.cmbchina.managedb.domain.CheckContext;
import com.cmbchina.managedb.domain.DatabaseSchemaPort;
import com.cmbchina.managedb.domain.DatasourceResource;
import com.cmbchina.managedb.domain.ResourceErrorCode;
import com.cmbchina.managedb.domain.ResourceStatus;
import com.cmbchina.managedb.domain.SqlExecutionResult;
import com.cmbchina.managedb.domain.TableBinding;
import com.cmbchina.managedb.domain.TableOperation;
import com.cmbchina.managedb.domain.TableStructure;
import com.cmbchina.managedb.domain.TableTaskDispatcher;
import com.cmbchina.managedb.domain.repository.TableOperationRepository;
import com.cmbchina.managedb.domain.valueobject.DatasourceKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 使用仓储和数据库替身覆盖确认、防串应用、部分失败及未知，不执行真实删表。
 */
class ResourceCleanupServiceTest {
    private static final String APP = "12ab34cd56ef";
    private TableOperationRepository repository;
    private DatabaseSchemaPort schemas;
    private TableTaskDispatcher dispatcher;
    private DatasourceResource resource;
    private ResourceCleanupService service;
    private final Map<String, TableStructure> physical = new HashMap<>();
    private final Map<String, TableBinding> bindings = new LinkedHashMap<>();
    private final AtomicReference<TableOperation> accepted = new AtomicReference<>();
    private final AtomicReference<Runnable> task = new AtomicReference<>();

    /**
     * 预留任务不立即运行，测试显式执行一次来观察已受理与终态的区别。
     */
    @BeforeEach
    void setUp() {
        repository = mock(TableOperationRepository.class);
        schemas = mock(DatabaseSchemaPort.class);
        dispatcher = mock(TableTaskDispatcher.class);
        resource = DatasourceResource.register(new DatasourceKey(APP), 2L, null, "注册测试", Actor.system());
        service = new ResourceCleanupService(repository, schemas, dispatcher);
        when(repository.checkContext(APP)).thenAnswer(a -> new CheckContext(resource, accepted.get() == null ? 0 : 1));
        when(repository.nextId()).thenReturn(101L);
        when(repository.cleanupBindings(APP)).thenAnswer(a -> new ArrayList<>(bindings.values()));
        when(repository.binding(eq(2L), anyString())).thenAnswer(a -> Optional.ofNullable(bindings.get(a.getArgument(1))));
        when(schemas.tableKey(eq(2L), anyString())).thenAnswer(a -> a.getArgument(1));
        when(schemas.readTable(eq(2L), anyString())).thenAnswer(a -> Optional.ofNullable(physical.get(a.getArgument(1))));
        when(schemas.execute(eq(2L), anyString())).thenAnswer(a -> {
            String sql = a.getArgument(1);
            physical.remove(sql.substring(12, sql.length() - 1));
            return new SqlExecutionResult(SqlExecutionResult.Outcome.ACKNOWLEDGED, null);
        });
        doAnswer(a -> {
            TableOperation op = a.getArgument(0);
            accepted.set(op);
            resource.claim(op.getId());
            return null;
        }).when(repository).beginCleanup(any(), eq(0L));
        when(repository.detail(eq(APP), eq(101L))).thenAnswer(a -> Optional.ofNullable(accepted.get()));
        doAnswer(a -> {
            TableOperation op = a.getArgument(0);
            TableBinding.Status status = a.getArgument(1);
            if (status != null) {
                for (int i = 0; i < op.getSteps().size(); i++) {
                    if (op.getSteps().get(i).getStatus() == TableOperation.StepStatus.SUCCEEDED) {
                        bindings.get(op.getTableNames().get(i)).dropped();
                    }
                }
            }
            return null;
        }).when(repository).saveStep(any(), any());
        doAnswer(a -> {
            TableOperation op = a.getArgument(0);
            if (op.getStatus() == TableOperation.Status.SUCCEEDED && op.getMode() == TableOperation.Mode.RELEASE) {
                resource.releaseResources(op.getId());
            } else if (op.getStatus() != TableOperation.Status.UNKNOWN) {
                resource.releaseOperation(op.getId());
            }
            return null;
        }).when(repository).finish(any());
        TableTaskDispatcher.Reservation slot = mock(TableTaskDispatcher.Reservation.class);
        when(dispatcher.reserve()).thenReturn(slot);
        doAnswer(a -> {
            task.set(a.getArgument(0));
            return null;
        }).when(slot).dispatch(any());
    }

    /**
     * 构造可选外键，真实结构与目录状态分开维护。
     */
    private void table(String name, String... references) {
        bindings.put(name, new TableBinding(APP, 2L, name, TableBinding.Status.CREATED, false));
        List<TableStructure.ForeignKey> keys = new ArrayList<>();
        for (String target : references) {
            keys.add(
                    new TableStructure.ForeignKey("fk", Collections.singletonList("parent_id"), target, Collections.singletonList("id"), null, null));
        }
        physical.put(name, new TableStructure(name, Collections.emptyMap(), Collections.emptyList(), keys, Collections.emptyMap()));
    }

    /**
     * 使用真实预览指纹提交，禁止测试绕过确认流程。
     */
    private void submit(String scope, String name) {
        ResourceCleanupPreview preview = service.preview(APP, scope, name);
        assertEquals("RUNNING", service.confirm(APP, scope, name,
                new CleanupConfirmation(preview.getPreviewHash(), true, true), Actor.system()).getStatus());
    }

    /**
     * 只读预览不能执行 SQL，整份数据源资源依赖顺序必须子表在前。
     */
    @Test
    void previewsExactOwnedTablesInDependencyOrder() {
        table("parent");
        table("child", "parent");
        ResourceCleanupPreview preview = service.preview(APP, "DATASOURCE", null);
        assertEquals(Arrays.asList("child", "parent"), preview.getTableNames());
        assertEquals(64, preview.getPreviewHash().length());
        verify(schemas, never()).execute(anyLong(), anyString());
        verify(repository, never()).beginCleanup(any(), anyLong());
    }

    /**
     * 未确认、未同意丢数据以及过期指纹不能认领或调度。
     */
    @Test
    void refusesMissingConsentAndStalePreview() {
        table("orders");
        String hash = service.preview(APP, "DATASOURCE", null).getPreviewHash();
        assertThrows(BizException.class, () -> service.confirm(APP, "DATASOURCE", null,
                new CleanupConfirmation(hash, false, true), Actor.system()));
        assertThrows(BizException.class, () -> service.confirm(APP, "DATASOURCE", null,
                new CleanupConfirmation(hash, true, false), Actor.system()));
        table("items");
        assertSame(ResourceErrorCode.CLEANUP_PREVIEW_CHANGED, assertThrows(BizException.class,
                () -> service.confirm(APP, "DATASOURCE", null,
                        new CleanupConfirmation(hash, true, true), Actor.system())).getErrorCode());
        verifyNoInteractions(dispatcher);
    }

    /**
     * 单表删除不能认领别人的表或目录外表，也不通过前缀模糊匹配。
     */
    @Test
    void rejectsForeignAndUnmanagedTables() {
        bindings.put("foreign", new TableBinding("ab1234cd56ef", 2L, "foreign", TableBinding.Status.CREATED, false));
        assertThrows(BizException.class, () -> service.preview(APP, "TABLE", "foreign"));
        assertThrows(BizException.class, () -> service.preview(APP, "TABLE", "missing"));
        verify(schemas, never()).readTable(anyLong(), anyString());
    }

    /**
     * 受理后只删除目标表，单表操作不能结束数据源资源注册。
     */
    @Test
    void dropsOneTableAndKeepsApplicationActive() {
        table("orders");
        table("untouched");
        submit("TABLE", "orders");
        assertTrue(physical.containsKey("orders"));
        task.get().run();
        assertFalse(physical.containsKey("orders"));
        assertTrue(physical.containsKey("untouched"));
        assertEquals(TableOperation.Status.SUCCEEDED, accepted.get().getStatus());
        assertEquals(ResourceStatus.REGISTERED, resource.getStatus());
        assertNull(resource.getActiveOperationId());
        assertEquals(TableBinding.Status.DROPPED, bindings.get("orders").getStatus());
    }

    /**
     * 回读全成功后才能 RELEASED，历史状态在应用释放后仍可查询。
     */
    @Test
    void releasesOnlyAfterAllTablesAreGone() {
        table("parent");
        table("child", "parent");
        submit("DATASOURCE", null);
        task.get().run();
        assertTrue(physical.isEmpty());
        assertEquals(ResourceStatus.RELEASED, resource.getStatus());
        assertEquals("SUCCEEDED", service.status(APP, 101L).getStatus());
        org.mockito.InOrder order = inOrder(schemas);
        order.verify(schemas).execute(2L, "DROP TABLE `child`");
        order.verify(schemas).execute(2L, "DROP TABLE `parent`");
    }

    /**
     * 无表应用也必须保存受理、完成事实，再结束注册状态。
     */
    @Test
    void releasesEmptyApplicationWithoutSql() {
        submit("DATASOURCE", null);
        task.get().run();
        assertEquals(ResourceStatus.RELEASED, resource.getStatus());
        verify(schemas, never()).execute(anyLong(), anyString());
        verify(repository, times(2)).saveStep(any(), isNull());
    }

    /**
     * SQL 结果未知保留占用，不删除后续表，也不把应用标记释放。
     */
    @Test
    void unknownExecutionStopsAndRetainsOccupancy() {
        table("a");
        table("b");
        doReturn(new SqlExecutionResult(SqlExecutionResult.Outcome.UNKNOWN, "EXECUTION_UNKNOWN")).when(schemas).execute(2L, "DROP TABLE `a`");
        submit("DATASOURCE", null);
        task.get().run();
        assertEquals(TableOperation.Status.UNKNOWN, accepted.get().getStatus());
        assertEquals(Long.valueOf(101L), resource.getActiveOperationId());
        assertEquals(ResourceStatus.REGISTERED, resource.getStatus());
        verify(schemas, never()).execute(2L, "DROP TABLE `b`");
    }

    /**
     * 外部引用不能绕过外键保护，部分已删除的事实仍保留。
     */
    @Test
    void partialFailureKeepsApplicationRegistered() {
        table("a");
        table("b");
        when(schemas.hasIncomingReferences(2L, "b")).thenReturn(true);
        submit("DATASOURCE", null);
        task.get().run();
        assertFalse(physical.containsKey("a"));
        assertTrue(physical.containsKey("b"));
        assertEquals(TableOperation.Status.FAILED, accepted.get().getStatus());
        assertEquals(ResourceStatus.REGISTERED, resource.getStatus());
        assertNull(resource.getActiveOperationId());
        verify(schemas, never()).execute(2L, "DROP TABLE `b`");
    }

    /**
     * 真实缺表可回读为 DROPPED，但不能发出多余 DROP。
     */
    @Test
    void confirmsAlreadyMissingOwnedTable() {
        table("orders");
        physical.remove("orders");
        submit("TABLE", "orders");
        task.get().run();
        assertEquals(TableOperation.Status.SUCCEEDED, accepted.get().getStatus());
        verify(schemas, never()).execute(anyLong(), anyString());
    }

    /**
     * 循环引用在确认前拒绝；软删目录不能被跳过后误报全应用清理成功。
     */
    @Test
    void refusesCyclesAndDeletedBindings() {
        table("a", "b");
        table("b", "a");
        assertSame(ResourceErrorCode.TABLE_REFERENCED,
                assertThrows(BizException.class, () -> service.preview(APP, "DATASOURCE", null)).getErrorCode());
        bindings.clear();
        bindings.put("hidden", new TableBinding(APP, 2L, "hidden", TableBinding.Status.CREATED, true));
        assertThrows(BizException.class, () -> service.preview(APP, "DATASOURCE", null));
        verifyNoInteractions(dispatcher);
    }
}
