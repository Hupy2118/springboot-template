package com.cmbchina.managedb.application;

import com.cmb.bee.commons.exceptions.BizException;
import com.cmbchina.managedb.domain.Actor;

import com.cmbchina.managedb.application.view.TableDataView;
import com.cmbchina.managedb.domain.CheckContext;
import com.cmbchina.managedb.domain.DatabaseSchemaPort;
import com.cmbchina.managedb.domain.DatasourceResource;
import com.cmbchina.managedb.domain.ResourceErrorCode;
import com.cmbchina.managedb.domain.ResourceStatus;
import com.cmbchina.managedb.domain.TableBinding;
import com.cmbchina.managedb.domain.TableDataPage;
import com.cmbchina.managedb.domain.TableDataQuery;
import com.cmbchina.managedb.domain.TableDataQueryPort;
import com.cmbchina.managedb.domain.TableMetadata;
import com.cmbchina.managedb.domain.repository.TableOperationRepository;
import com.cmbchina.managedb.domain.valueobject.DatasourceKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 精确归属、读取前后校验及分页语义测试，不连接数据库。
 */
class DatasourceTableDataServiceTest {
    private static final String APP = "12ab34cd56ef", TABLE = APP + "_orders";
    private TableOperationRepository repository;
    private DatabaseSchemaPort schemas;
    private TableDataQueryPort reader;
    private DatasourceResource resource;
    private DatasourceTableDataService service;
    private TableMetadata table;

    /**
     * 准备单个真实表替身及注册应用，字段和目录相互独立。
     */
    @BeforeEach
    void setUp() {
        repository = mock(TableOperationRepository.class);
        schemas = mock(DatabaseSchemaPort.class);
        reader = mock(TableDataQueryPort.class);
        resource = DatasourceResource.register(new DatasourceKey(APP), 2L, null, "注册测试", Actor.system());
        service = new DatasourceTableDataService(repository, schemas, reader);
        table = new TableMetadata(TABLE, "订单",
                Collections.singletonList(new TableMetadata.Column("id", "bigint", false, true, true, null, "主键")));
        when(repository.checkContext(APP)).thenReturn(new CheckContext(resource, 0L));
        when(schemas.tableKey(eq(2L), anyString())).thenAnswer(call -> call.getArgument(1));
        when(repository.binding(2L, TABLE)).thenReturn(Optional.of(new TableBinding(APP, 2L, TABLE, TableBinding.Status.CREATED, false)));
        when(schemas.readTableMetadata(2L, TABLE)).thenReturn(Optional.of(table));
        when(reader.read(eq(2L), eq(table), any())).thenReturn(
                new TableDataPage(Collections.emptyList(), Collections.singletonMap("id", "TEXT"), false, false));
    }

    /**
     * 一期标准分页意图不包含任何 SQL 或真实挂载。
     */
    private TableDataQuery query() {
        return new TableDataQuery(1, 20, new TableDataQuery.Filter("AND", Collections.emptyList()));
    }

    /**
     * 空表是合法真实结果，仍返回字段，不用异常伪造空数据。
     */
    @Test
    void returnsMetadataForEmptyTable() {
        TableDataView result = service.query(APP, TABLE, query());
        assertEquals("SUCCEEDED", result.getStatus());
        assertEquals(APP, result.getDatasourceKey());
        assertEquals(TABLE, result.getTableName());
        assertEquals(1, result.getColumns().size());
        assertTrue(result.getRows().isEmpty());
        assertFalse(result.isHasMore());
        assertFalse(result.isFilteringSupported());
        verify(repository, times(2)).binding(2L, TABLE);
    }

    /**
     * bigint 文本不经过浮点转换，null 保留原语义。
     */
    @Test
    void preservesExactCellTextAndNull() {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("id", "18446744073709551615");
        row.put("reason", null);
        when(reader.read(eq(2L), eq(table), any())).thenReturn(
                new TableDataPage(Collections.singletonList(row), Collections.singletonMap("id", "TEXT"), true, false));
        TableDataView result = service.query(APP, TABLE, query());
        assertEquals("18446744073709551615", result.getRows().get(0).get("id"));
        assertNull(result.getRows().get(0).get("reason"));
        assertTrue(result.isHasMore());
        assertThrows(UnsupportedOperationException.class, () -> result.getRows().get(0).put("id", "changed"));
    }

    /**
     * 非空条件必须明确失败，不能被忽略后读取全表。
     */
    @Test
    void rejectsReservedFiltersBeforeAnyResourceRead() {
        TableDataQuery filtered = new TableDataQuery(1, 20, new TableDataQuery.Filter("AND", Collections.singletonList(
                new TableDataQuery.Condition("leave_type", "EQ", Collections.singletonList("年假")))));
        assertSame(ResourceErrorCode.QUERY_FILTER_UNSUPPORTED,
                assertThrows(BizException.class, () -> service.query(APP, TABLE, filtered)).getErrorCode());
        verifyNoInteractions(repository, schemas, reader);
    }

    /**
     * 页大小、负页码及溢出的深分页不能进入数据库。
     */
    @Test
    void rejectsInvalidPagination() {
        for (int[] pair : new int[][]{{0, 20}, {1, 101}, {Integer.MAX_VALUE, 100}, {100002, 1}}) {
            assertThrows(BizException.class, () -> new TableDataQuery(pair[0], pair[1], new TableDataQuery.Filter("AND", Collections.emptyList())));
        }
        assertEquals(100000L, new TableDataQuery(1001, 100, new TableDataQuery.Filter("AND", Collections.emptyList())).offset());
        verifyNoInteractions(reader);
    }

    /**
     * 他人表、目录外表、软删、未建成和错误挂载均不能读取数据。
     */
    @Test
    void refusesUnreadableBindings() {
        List<Optional<TableBinding>> cases = Arrays.asList(Optional.empty(),
                Optional.of(new TableBinding("ab1234cd56ef", 2L, TABLE, TableBinding.Status.CREATED, false)),
                Optional.of(new TableBinding(APP, 3L, TABLE, TableBinding.Status.CREATED, false)),
                Optional.of(new TableBinding(APP, 2L, TABLE, TableBinding.Status.CREATED, true)),
                Optional.of(new TableBinding(APP, 2L, TABLE, TableBinding.Status.DROPPED, false)));
        for (Optional<TableBinding> binding : cases) {
            when(repository.binding(2L, TABLE)).thenReturn(binding);
            assertThrows(BizException.class, () -> service.query(APP, TABLE, query()));
        }
        verifyNoInteractions(reader);
        verify(schemas, never()).readTableMetadata(anyLong(), anyString());
    }

    /**
     * 释放或忙碌应用在目标数据库访问之前被拒绝。
     */
    @Test
    void refusesBusyAndReleasedApplications() {
        resource.claim(101L);
        assertSame(ResourceErrorCode.RESOURCE_BUSY, assertThrows(BizException.class, () -> service.query(APP, TABLE, query())).getErrorCode());
        when(repository.checkContext(APP)).thenReturn(
                new CheckContext(DatasourceResource.restore(new DatasourceKey(APP), 2L, null, null, null, null, ResourceStatus.RELEASED, false, null), 0L));
        assertThrows(BizException.class, () -> service.query(APP, TABLE, query()));
        verifyNoInteractions(schemas, reader);
    }

    /**
     * 查询期间受管操作发生时丢弃结果，不能返回重建前的一页旧数据。
     */
    @Test
    void discardsPageIfOperationCountChanges() {
        when(repository.checkContext(APP)).thenReturn(new CheckContext(resource, 0L), new CheckContext(resource, 1L));
        assertSame(ResourceErrorCode.RESOURCE_BUSY, assertThrows(BizException.class, () -> service.query(APP, TABLE, query())).getErrorCode());
        verify(reader).read(eq(2L), eq(table), any());
    }

    /**
     * 真实缺表不能被解释为合法空表。
     */
    @Test
    void refusesMissingPhysicalTable() {
        when(schemas.readTableMetadata(2L, TABLE)).thenReturn(Optional.empty());
        assertSame(ResourceErrorCode.RESOURCE_NOT_FOUND,
                assertThrows(BizException.class, () -> service.query(APP, TABLE, query())).getErrorCode());
        verifyNoInteractions(reader);
    }
}
