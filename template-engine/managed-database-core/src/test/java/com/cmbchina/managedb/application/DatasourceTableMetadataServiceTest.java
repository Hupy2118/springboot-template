package com.cmbchina.managedb.application;

import com.cmb.bee.commons.exceptions.BizException;
import com.cmbchina.managedb.domain.Actor;

import com.cmbchina.managedb.application.view.DatasourceTablesView;
import com.cmbchina.managedb.application.view.TableColumnsView;
import com.cmbchina.managedb.domain.CheckContext;
import com.cmbchina.managedb.domain.DatabaseSchemaPort;
import com.cmbchina.managedb.domain.DatasourceResource;
import com.cmbchina.managedb.domain.ResourceErrorCode;
import com.cmbchina.managedb.domain.ResourceStatus;
import com.cmbchina.managedb.domain.TableBinding;
import com.cmbchina.managedb.domain.TableMetadata;
import com.cmbchina.managedb.domain.repository.TableOperationRepository;
import com.cmbchina.managedb.domain.valueobject.DatasourceKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 元数据只来自当前有效资源拥有的真实表，不连接目标数据库。
 */
class DatasourceTableMetadataServiceTest {
    private static final String APP = "b0ddb23acc91", OTHER = "a0ddb23acc91";
    private TableOperationRepository repository;
    private DatabaseSchemaPort schemas;
    private DatasourceResource resource;
    private DatasourceTableMetadataService service;

    /**
     * 建立当前数据源资源及无占用读快照，目录和真实表分别模拟。
     */
    @BeforeEach
    void setUp() {
        repository = mock(TableOperationRepository.class);
        schemas = mock(DatabaseSchemaPort.class);
        resource = DatasourceResource.register(new DatasourceKey(APP), 2L, null, "注册测试", Actor.system());
        when(repository.checkContext(APP)).thenReturn(new CheckContext(resource, 0L));
        when(schemas.tableKey(eq(2L), anyString())).thenAnswer(a -> a.getArgument(1));
        service = new DatasourceTableMetadataService(repository, schemas);
    }

    /**
     * 构造真实读取结果，包含原始默认值和列注释。
     */
    private TableMetadata actual(String name) {
        return new TableMetadata(name, "订单表", Collections.singletonList(
                new TableMetadata.Column("is_deleted", "tinyint(1)", false, false, false, "0", "是否删除")));
    }

    /**
     * 构造目录记录，状态与真实结构分开。
     */
    private TableBinding owned(String name, TableBinding.Status status, boolean deleted) {
        return new TableBinding(APP, 2L, name, status, deleted);
    }

    /**
     * 目录成功、真实存在两者同时满足才展示，不把读取缺失伪装为存在。
     */
    @Test
    void listsOnlyCreatedOwnedAndExistingTables() {
        when(repository.bindings(APP)).thenReturn(Arrays.asList(
                owned("orders", TableBinding.Status.CREATED, false),
                owned("missing", TableBinding.Status.CREATED, false),
                owned("draft", TableBinding.Status.REGISTERED, false),
                owned("dropped", TableBinding.Status.DROPPED, false),
                owned("deleted", TableBinding.Status.CREATED, true)));
        when(schemas.readTableMetadata(2L, "orders")).thenReturn(Optional.of(actual("orders")));
        when(schemas.readTableMetadata(2L, "missing")).thenReturn(Optional.empty());
        DatasourceTablesView result = service.tables(APP);
        assertEquals(APP, result.getDatasourceKey());
        assertEquals(1, result.getTables().size());
        assertEquals("orders", result.getTables().get(0).getTableName());
        assertEquals("订单表", result.getTables().get(0).getDescription());
        assertEquals(1, result.getTables().get(0).getColumnCount());
        verify(schemas, times(2)).readTableMetadata(eq(2L), anyString());
        verify(schemas, never()).execute(anyLong(), anyString());
    }

    /**
     * 读取真实字段，默认值不变成比较器内部的 NONE。
     */
    @Test
    void returnsActualColumnsOnlyAfterOwnershipValidation() {
        when(repository.binding(2L, "orders")).thenReturn(Optional.of(owned("orders", TableBinding.Status.CREATED, false)));
        when(schemas.readTableMetadata(2L, "orders")).thenReturn(Optional.of(actual("orders")));
        TableColumnsView result = service.columns(APP, "orders");
        assertEquals("0", result.getColumns().get(0).getDefaultValue());
        assertEquals("是否删除", result.getColumns().get(0).getDescription());
        assertEquals("tinyint(1)", result.getColumns().get(0).getType());
        verify(repository, times(2)).binding(2L, "orders");
    }

    /**
     * 他人表、软删、未建成和目录外表均不能触发真实字段读取。
     */
    @Test
    void rejectsForeignDeletedUncreatedAndUnmanagedTables() {
        List<Optional<TableBinding>> bindings = Arrays.asList(
                Optional.of(new TableBinding(OTHER, 2L, "orders", TableBinding.Status.CREATED, false)),
                Optional.of(owned("orders", TableBinding.Status.CREATED, true)),
                Optional.of(owned("orders", TableBinding.Status.REGISTERED, false)),
                Optional.of(owned("orders", TableBinding.Status.DROPPED, false)), Optional.empty());
        for (Optional<TableBinding> binding : bindings) {
            when(repository.binding(2L, "orders")).thenReturn(binding);
            assertThrows(BizException.class, () -> service.columns(APP, "orders"));
        }
        verify(schemas, never()).readTableMetadata(anyLong(), anyString());
    }

    /**
     * 错误挂载不能通过同应用名绕过精确位置归属。
     */
    @Test
    void rejectsDirectoryFromAnotherMount() {
        when(repository.bindings(APP)).thenReturn(Collections.singletonList(
                new TableBinding(APP, 3L, "orders", TableBinding.Status.CREATED, false)));
        assertEquals(ResourceErrorCode.TABLE_BINDING_CONFLICT,
                assertThrows(BizException.class, () -> service.tables(APP)).getErrorCode());
        verifyNoInteractions(schemas);
    }

    /**
     * 真实缺表返回明确 404 语义，数据库失败则保留不可访问语义。
     */
    @Test
    void distinguishesMissingTableFromDatabaseFailure() {
        when(repository.binding(2L, "orders")).thenReturn(Optional.of(owned("orders", TableBinding.Status.CREATED, false)));
        when(schemas.readTableMetadata(2L, "orders")).thenReturn(Optional.empty());
        assertEquals(ResourceErrorCode.RESOURCE_NOT_FOUND,
                assertThrows(BizException.class, () -> service.columns(APP, "orders")).getErrorCode());
        when(repository.bindings(APP)).thenReturn(Collections.singletonList(owned("orders", TableBinding.Status.CREATED, false)));
        when(schemas.readTableMetadata(2L, "orders")).thenThrow(new BizException(ResourceErrorCode.DATABASE_UNAVAILABLE));
        assertEquals(ResourceErrorCode.DATABASE_UNAVAILABLE,
                assertThrows(BizException.class, () -> service.tables(APP)).getErrorCode());
    }

    /**
     * 应用有在途或未知占用时不读取可能处于重建中的表。
     */
    @Test
    void blocksActiveOperationsBeforeReadingMetadata() {
        resource.claim(101L);
        assertEquals(ResourceErrorCode.RESOURCE_BUSY,
                assertThrows(BizException.class, () -> service.tables(APP)).getErrorCode());
        verifyNoInteractions(schemas);
    }

    /**
     * 查询期间发生写入，即使最后已释放占用，也不能把旧结果返回成功。
     */
    @Test
    void rejectsChangesDuringMetadataRead() {
        when(repository.checkContext(APP)).thenReturn(new CheckContext(resource, 0L), new CheckContext(resource, 1L));
        when(repository.bindings(APP)).thenReturn(Collections.singletonList(owned("orders", TableBinding.Status.CREATED, false)));
        when(schemas.readTableMetadata(2L, "orders")).thenReturn(Optional.of(actual("orders")));
        assertEquals(ResourceErrorCode.RESOURCE_BUSY,
                assertThrows(BizException.class, () -> service.tables(APP)).getErrorCode());
    }

    /**
     * 已释放或软删除应用不能通过历史目录查询真实库。
     */
    @Test
    void rejectsReleasedApplication() {
        DatasourceResource released = DatasourceResource.restore(new DatasourceKey(APP), 2L, null, null, null, null, ResourceStatus.RELEASED, false, null);
        when(repository.checkContext(APP)).thenReturn(new CheckContext(released, 0L));
        assertEquals(ResourceErrorCode.RESOURCE_NOT_FOUND,
                assertThrows(BizException.class, () -> service.tables(APP)).getErrorCode());
        verifyNoInteractions(schemas);
    }
}
