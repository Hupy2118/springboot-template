package com.cmbchina.managedb.application;

import com.cmb.bee.commons.exceptions.BizException;
import com.cmbchina.managedb.domain.Actor;

import com.cmbchina.managedb.application.view.TableOperationReceipt;
import com.cmbchina.managedb.application.view.TableReadinessView;
import com.cmbchina.managedb.domain.CheckContext;
import com.cmbchina.managedb.domain.DatabaseSchemaPort;
import com.cmbchina.managedb.domain.DatasourceResource;
import com.cmbchina.managedb.domain.ParsedCreateTable;
import com.cmbchina.managedb.domain.ResourceErrorCode;
import com.cmbchina.managedb.domain.SqlExecutionResult;
import com.cmbchina.managedb.domain.TableBinding;
import com.cmbchina.managedb.domain.TableOperation;
import com.cmbchina.managedb.domain.TableStructure;
import com.cmbchina.managedb.domain.TableStructureComparator;
import com.cmbchina.managedb.domain.TableSyncSettings;
import com.cmbchina.managedb.domain.TableTaskDispatcher;
import com.cmbchina.managedb.domain.repository.TableOperationRepository;
import com.cmbchina.managedb.domain.valueobject.DatasourceKey;
import com.cmbchina.managedb.infrastructure.persistence.TableOperationCodec;
import com.cmbchina.managedb.infrastructure.persistence.po.TableOperationPo;
import com.cmbchina.managedb.infrastructure.sql.MySqlCreateTableParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 用真实解析比较及独立持久化快照验证业务链路，不连接数据库或启动线程。
 */
class TableSynchronizationTest {
    private static final String APP = "b0ddb23acc91";
    private static final String SQL = "CREATE TABLE orders(id bigint NOT NULL, title varchar(64), PRIMARY KEY(id))";
    private final MySqlCreateTableParser parser = new MySqlCreateTableParser();
    private final TableOperationCodec codec = new TableOperationCodec(new ObjectMapper());
    private TableOperationRepository repository;
    private DatabaseSchemaPort schemas;
    private TableTaskDispatcher dispatcher;
    private TableTaskDispatcher.Reservation slot;
    private TableOperationService operations;
    private TableReadinessService readiness;
    private DatasourceResource resource;
    private TableOperation stored;
    private Runnable dispatched;
    private final Map<String, TableBinding> bindings = new HashMap<>();
    private final Map<String, TableStructure> physical = new HashMap<>();
    private long count;
    private boolean rejectCreate, unknownCreate, defer;

    /**
     * 模拟受理短事务及逐步保存，恢复时复制记录以避免内存引用掩盖落库失败。
     */
    @BeforeEach
    void setUp() {
        repository = mock(TableOperationRepository.class);
        schemas = mock(DatabaseSchemaPort.class);
        dispatcher = mock(TableTaskDispatcher.class);
        slot = mock(TableTaskDispatcher.Reservation.class);
        resource = DatasourceResource.register(new DatasourceKey(APP), 1L, null, "注册测试", Actor.system());
        when(repository.nextId()).thenReturn(101L);
        when(repository.checkContext(APP)).thenAnswer(a -> new CheckContext(resource, count));
        when(repository.binding(eq(1L), anyString())).thenAnswer(a -> Optional.ofNullable(bindings.get(a.getArgument(1))));
        when(repository.bindings(APP)).thenAnswer(a -> new ArrayList<>(bindings.values()));
        when(repository.detail(APP, 101L)).thenAnswer(a -> Optional.ofNullable(stored == null ? null : copy(stored)));
        doAnswer(a -> {
            TableOperation op = a.getArgument(0);
            resource.claim(op.getId());
            count++;
            bindings.putIfAbsent(op.getTableName(), new TableBinding(APP, 1L, op.getTableName(), TableBinding.Status.REGISTERED, false));
            stored = copy(op);
            return null;
        }).when(repository).begin(any(), any());
        doAnswer(a -> {
            TableOperation op = a.getArgument(0);
            TableBinding.Status status = a.getArgument(1);
            stored = copy(op);
            if (status == TableBinding.Status.CREATED) {
                bindings.get(op.getTableName()).created();
            }
            if (status == TableBinding.Status.DROPPED) {
                bindings.get(op.getTableName()).dropped();
            }
            return null;
        }).when(repository).saveStep(any(), any());
        doAnswer(a -> {
            TableOperation op = a.getArgument(0);
            stored = copy(op);
            if (op.getStatus() != TableOperation.Status.UNKNOWN) {
                resource.releaseOperation(op.getId());
            }
            return null;
        }).when(repository).finish(any());
        when(schemas.parseCreate(anyString())).thenAnswer(a -> parser.parse(a.getArgument(0)));
        when(schemas.tableKey(eq(1L), anyString())).thenAnswer(a -> ((String)a.getArgument(1)).toLowerCase(Locale.ROOT));
        when(schemas.readTable(eq(1L), anyString())).thenAnswer(a -> Optional.ofNullable(physical.get(a.getArgument(1))));
        when(schemas.execute(eq(1L), anyString())).thenAnswer(a -> execute(a.getArgument(1)));
        when(dispatcher.reserve()).thenReturn(slot);
        doAnswer(a -> {
            dispatched = a.getArgument(0);
            if (!defer) {
                dispatched.run();
            }
            return null;
        }).when(slot).dispatch(any());
        TableSyncSettings settings = new TableSyncSettings(65536, 200, 4194304);
        TableSqlSupport support = new TableSqlSupport(schemas, repository, settings);
        TableStructureComparator comparator = new TableStructureComparator();
        operations = new TableOperationService(repository, schemas, support, comparator, dispatcher);
        readiness = new TableReadinessService(repository, schemas, support, comparator, settings);
    }

    /**
     * 独立序列化快照模拟真正落库及重新读取，不把可变对象当数据库。
     */
    private TableOperation copy(TableOperation op) {
        TableOperationPo row = new TableOperationPo();
        row.setId(op.getId());
        row.setDatasourceKey(op.getDatasourceKey());
        row.setDatasourceId(op.getDatasourceId());
        row.setActionType(op.getMode() == TableOperation.Mode.CREATE ? "CREATE_TABLE" : "REBUILD_TABLE");
        row.setStatus(op.getStatus().name());
        row.setCreatedAt(op.getCreatedAt());
        row.setExecutionJson(codec.content(op));
        row.setExecutionResult(codec.result(op));
        return codec.restore(row);
    }

    /**
     * 只在测试内模拟数据库的确定返回及真实表存在性变化。
     */
    private SqlExecutionResult execute(String sql) {
        if (sql.startsWith("DROP TABLE")) {
            physical.remove("orders");
        } else {
            if (unknownCreate) {
                return new SqlExecutionResult(SqlExecutionResult.Outcome.UNKNOWN, "EXECUTION_UNKNOWN");
            }
            if (rejectCreate) {
                return new SqlExecutionResult(SqlExecutionResult.Outcome.REJECTED, "SQL_EXECUTION_FAILED");
            }
            physical.put("orders", parser.parse(sql).getStructure());
        }
        return new SqlExecutionResult(SqlExecutionResult.Outcome.ACKNOWLEDGED, null);
    }

    /**
     * 已有合法表同时提供目录与物理结构。
     */
    private void ownedTable() {
        bindings.put("orders", new TableBinding(APP, 1L, "orders", TableBinding.Status.CREATED, false));
        physical.put("orders", parser.parse(SQL).getStructure());
    }

    /**
     * 无主键、唯一索引和注释中的主键字样都不能进入受理事务。
     */
    @Test
    void rejectsCreateWithoutExplicitPrimaryKeyBeforeAcceptance() {
        for (String sql : Arrays.asList(
                "CREATE TABLE orders(id bigint)",
                "CREATE TABLE orders(id bigint NOT NULL, UNIQUE KEY uk_id(id))",
                "CREATE TABLE orders(id bigint COMMENT 'PRIMARY KEY')",
                "CREATE TABLE orders(id bigint AUTO_INCREMENT, KEY idx_id(id))")) {
            assertSame(ResourceErrorCode.PRIMARY_KEY_REQUIRED, assertThrows(BizException.class,
                    () -> operations.submit(APP, "CREATE", sql, false, Actor.system())).getErrorCode());
        }
        verify(repository, never()).begin(any(), any());
        verifyNoInteractions(dispatcher);
        verify(schemas, never()).readTable(anyLong(), anyString());
        verify(schemas, never()).execute(anyLong(), anyString());
        assertNull(stored);
        assertTrue(bindings.isEmpty());
        assertNull(resource.getActiveOperationId());
    }

    /**
     * 无主键重建在受理及 DROP 之前拒绝，保留原表和目录。
     */
    @Test
    void rejectsRebuildWithoutPrimaryKeyBeforeDrop() {
        ownedTable();
        assertSame(ResourceErrorCode.PRIMARY_KEY_REQUIRED, assertThrows(BizException.class,
                () -> operations.submit(APP, "REBUILD", "CREATE TABLE orders(id bigint)", true, Actor.system())).getErrorCode());
        verify(repository, never()).begin(any(), any());
        verifyNoInteractions(dispatcher);
        verify(schemas, never()).execute(anyLong(), anyString());
        assertTrue(physical.containsKey("orders"));
        assertEquals(TableBinding.Status.CREATED, bindings.get("orders").getStatus());
        assertNull(resource.getActiveOperationId());
    }

    /**
     * 启动清单无主键时同样拒绝，不能拿历史目录替代当前设计要求。
     */
    @Test
    void rejectsReadinessManifestWithoutPrimaryKey() {
        ownedTable();
        assertSame(ResourceErrorCode.PRIMARY_KEY_REQUIRED, assertThrows(BizException.class,
                () -> readiness.check(APP, Collections.singletonList("CREATE TABLE orders(id bigint)"))).getErrorCode());
        verify(schemas, never()).readTable(anyLong(), anyString());
        verify(repository, never()).begin(any(), any());
    }

    /**
     * 行内主键和联合主键都合法，保持用户原 SQL 和联合键列顺序。
     */
    @Test
    void acceptsInlineAndCompositeExplicitPrimaryKeys() {
        TableSqlSupport support = new TableSqlSupport(schemas, repository, new TableSyncSettings(65536, 200, 4194304));
        String inline = "CREATE TABLE orders(id bigint PRIMARY KEY)";
        ParsedCreateTable parsed = support.parse(1L, inline);
        assertEquals(inline, parsed.getSql());
        assertEquals(Collections.singletonList("id"), parsed.getStructure().getIndexes().get(0).getColumns());
        String composite = "CREATE TABLE orders(tenant_id bigint, id bigint, PRIMARY KEY(tenant_id,id))";
        parsed = support.parse(1L, composite);
        assertEquals(composite, parsed.getSql());
        assertEquals(Arrays.asList("tenant_id", "id"), parsed.getStructure().getIndexes().get(0).getColumns());
    }

    /**
     * 首次创建自动登记，成功回读后才能建成并释放占用。
     */
    @Test
    void createsAndTracksTable() {
        TableOperationReceipt receipt = operations.submit(APP, "CREATE", SQL, false, Actor.system());
        assertEquals("101", receipt.getOperationId());
        assertEquals(TableOperation.Status.SUCCEEDED, stored.getStatus());
        assertEquals(TableBinding.Status.CREATED, bindings.get("orders").getStatus());
        assertNull(resource.getActiveOperationId());
        verify(schemas).execute(1L, SQL);
        assertTrue(readiness.check(APP, Collections.singletonList(SQL)).isReady());
    }

    /**
     * 受理后尚未执行时 HTTP 回执不能被误认为建表成功。
     */
    @Test
    void acceptedTaskRetainsRunningOccupancy() {
        defer = true;
        assertEquals("RUNNING", operations.submit(APP, "CREATE", SQL, false, Actor.system()).getStatus());
        assertEquals(TableOperation.Status.RUNNING, stored.getStatus());
        assertEquals(Long.valueOf(101L), resource.getActiveOperationId());
        assertFalse(readiness.check(APP, Collections.singletonList(SQL)).isReady());
        verify(schemas, never()).execute(anyLong(), anyString());
    }

    /**
     * 仅原 CREATED 且结构一致才能满足目标而不再次执行。
     */
    @Test
    void existingIdenticalTableDoesNotExecuteSql() {
        ownedTable();
        operations.submit(APP, "CREATE", SQL, false, Actor.system());
        assertEquals(TableOperation.Status.SUCCEEDED, stored.getStatus());
        assertEquals(Boolean.FALSE, stored.getSteps().get(0).getVerification().get("sqlExecuted"));
        verify(schemas, never()).execute(anyLong(), anyString());
    }

    /**
     * 目录外真实表不能因结构相同被自动认领。
     */
    @Test
    void rejectsUnmanagedTable() {
        physical.put("orders", parser.parse(SQL).getStructure());
        assertSame(ResourceErrorCode.TABLE_BINDING_CONFLICT, assertThrows(BizException.class,
                () -> operations.submit(APP, "CREATE", SQL, false, Actor.system())).getErrorCode());
        verify(repository, never()).begin(any(), any());
    }

    /**
     * 其他数据源资源和软删目录都保留位置唯一性，且不读取其结构。
     */
    @Test
    void rejectsOtherOwnersAndDeletedBindings() {
        for (TableBinding binding : Arrays.asList(
                new TableBinding("aa1122334455", 1L, "orders", TableBinding.Status.CREATED, false),
                new TableBinding(APP, 1L, "orders", TableBinding.Status.CREATED, true))) {
            bindings.put("orders", binding);
            assertThrows(BizException.class, () -> operations.submit(APP, "CREATE", SQL, false, Actor.system()));
        }
        verify(schemas, never()).readTable(anyLong(), anyString());
        verify(repository, never()).begin(any(), any());
    }

    /**
     * 修改现有结构必须走明确重建，不将旧成功当作当前同步。
     */
    @Test
    void rejectsCreateWithDifferentStructure() {
        ownedTable();
        assertSame(ResourceErrorCode.STRUCTURE_MISMATCH, assertThrows(BizException.class,
                () -> operations.submit(APP, "CREATE", SQL.replace("varchar(64)", "varchar(32)"), false, Actor.system())).getErrorCode());
        verify(repository, never()).begin(any(), any());
    }

    /**
     * 重建必须明确丢弃数据且不存在其他表引用。
     */
    @Test
    void refusesRebuildBeforeDeletingWhenUnconfirmedOrReferenced() {
        ownedTable();
        assertThrows(BizException.class, () -> operations.submit(APP, "REBUILD", SQL, false, Actor.system()));
        when(schemas.hasIncomingReferences(1L, "orders")).thenReturn(true);
        assertThrows(BizException.class, () -> operations.submit(APP, "REBUILD", SQL, true, Actor.system()));
        verify(schemas, never()).execute(anyLong(), anyString());
    }

    /**
     * 两步全部回读确认后重建成功。
     */
    @Test
    void rebuildsOwnedTableInOrder() {
        ownedTable();
        operations.submit(APP, "REBUILD", SQL, true, Actor.system());
        assertEquals(TableOperation.Status.SUCCEEDED, stored.getStatus());
        assertEquals(2, stored.getSteps().size());
        org.mockito.InOrder order = inOrder(schemas);
        order.verify(schemas).execute(1L, "DROP TABLE `orders`");
        order.verify(schemas).execute(1L, SQL);
        assertEquals(TableBinding.Status.CREATED, bindings.get("orders").getStatus());
    }

    /**
     * 删除成功但建表明确失败时保存部分成功与缺表事实。
     */
    @Test
    void preservesDropWhenCreateIsRejected() {
        ownedTable();
        rejectCreate = true;
        operations.submit(APP, "REBUILD", SQL, true, Actor.system());
        assertEquals(TableOperation.Status.FAILED, stored.getStatus());
        assertEquals(TableOperation.StepStatus.SUCCEEDED, stored.getSteps().get(0).getStatus());
        assertEquals(TableOperation.StepStatus.FAILED, stored.getSteps().get(1).getStatus());
        assertEquals(TableBinding.Status.DROPPED, bindings.get("orders").getStatus());
        assertNull(resource.getActiveOperationId());
    }

    /**
     * 未知保留占用和无确定结束时间，查询与重复调度不会重放。
     */
    @Test
    void unknownHoldsApplicationAndDoesNotReplay() {
        unknownCreate = true;
        operations.submit(APP, "CREATE", SQL, false, Actor.system());
        assertEquals(TableOperation.Status.UNKNOWN, stored.getStatus());
        assertNull(stored.getFinishedAt());
        assertNotNull(resource.getActiveOperationId());
        operations.detail(APP, 101L);
        dispatched.run();
        assertThrows(BizException.class, () -> operations.submit(APP, "CREATE", SQL, false, Actor.system()));
        verify(schemas, times(1)).execute(anyLong(), anyString());
    }

    /**
     * 预检查和执行之间出现的真实表不能被已预留目录认领。
     */
    @Test
    void doesNotAdoptTableAppearingAfterAcceptance() {
        defer = true;
        operations.submit(APP, "CREATE", SQL, false, Actor.system());
        physical.put("orders", parser.parse(SQL).getStructure());
        dispatched.run();
        assertEquals(TableOperation.Status.FAILED, stored.getStatus());
        assertEquals(TableBinding.Status.REGISTERED, bindings.get("orders").getStatus());
        verify(schemas, never()).execute(anyLong(), anyString());
    }

    /**
     * 资源占用先于容量预留检查，客户端收到应用冲突而不是容量不足。
     */
    @Test
    void applicationOccupancyRemainsResourceBusy() {
        resource.claim(999L);
        assertSame(ResourceErrorCode.RESOURCE_BUSY, assertThrows(BizException.class,
                () -> operations.submit(APP, "CREATE", SQL, false, Actor.system())).getErrorCode());
        verifyNoInteractions(dispatcher);
        verify(repository, never()).begin(any(), any());
    }

    /**
     * 容量不足应在任何受理事务之前报错。
     */
    @Test
    void fullCapacityDoesNotCreateOperation() {
        when(dispatcher.reserve()).thenThrow(new BizException(ResourceErrorCode.EXECUTION_CAPACITY_EXHAUSTED));
        assertSame(ResourceErrorCode.EXECUTION_CAPACITY_EXHAUSTED, assertThrows(BizException.class,
                () -> operations.submit(APP, "CREATE", SQL, false, Actor.system())).getErrorCode());
        verify(repository, never()).begin(any(), any());
    }

    /**
     * 调度明确拒绝且未发送 SQL 时保存失败并归还资源占用。
     */
    @Test
    void rejectedDispatchReleasesApplication() {
        doThrow(new BizException(ResourceErrorCode.EXECUTION_CAPACITY_EXHAUSTED)).when(slot).dispatch(any());
        assertSame(ResourceErrorCode.EXECUTION_CAPACITY_EXHAUSTED, assertThrows(BizException.class,
                () -> operations.submit(APP, "CREATE", SQL, false, Actor.system())).getErrorCode());
        assertEquals(TableOperation.Status.FAILED, stored.getStatus());
        assertEquals("EXECUTION_CAPACITY_EXHAUSTED", stored.getErrorCode());
        assertEquals("EXECUTION_CAPACITY_EXHAUSTED", stored.getSteps().get(0).getErrorCode());
        assertNull(resource.getActiveOperationId());
        verify(schemas, never()).execute(anyLong(), anyString());
    }

    /**
     * 保存删表结果失败时不能继续发建表 SQL。
     */
    @Test
    void failedDropPersistenceStopsCreateAndKeepsOccupancy() {
        ownedTable();
        doAnswer(a -> {
            if (a.getArgument(1) != null) {
                throw new BizException(ResourceErrorCode.INTERNAL_ERROR);
            }
            stored = copy(a.getArgument(0));
            return null;
        })
                .when(repository).saveStep(any(), any());
        operations.submit(APP, "REBUILD", SQL, true, Actor.system());
        assertNotNull(resource.getActiveOperationId());
        assertEquals(TableOperation.Status.UNKNOWN, stored.getStatus());
        verify(schemas).execute(1L, "DROP TABLE `orders`");
        verify(schemas, never()).execute(1L, SQL);
    }

    /**
     * 检查比较当前 SQL，额外旧表展示但不阻断空模型。
     */
    @Test
    void readinessUsesCurrentManifest() {
        ownedTable();
        TableReadinessView mismatch = readiness.check(APP, Collections.singletonList(SQL.replace("varchar(64)", "varchar(32)")));
        assertFalse(mismatch.isReady());
        assertEquals("STRUCTURE_MISMATCH", mismatch.getTables().get(0).getStatus());
        TableReadinessView empty = readiness.check(APP, Collections.emptyList());
        assertTrue(empty.isReady());
        assertEquals(Collections.singletonList("orders"), empty.getExtraTables());
        verify(repository, never()).begin(any(), any());
    }

    /**
     * 缺表与目录外表冲突分别返回，不用历史记录兜底。
     */
    @Test
    void distinguishesMissingAndUnmanagedTables() {
        assertEquals("NOT_CREATED", readiness.check(APP, Collections.singletonList(SQL)).getTables().get(0).getStatus());
        physical.put("orders", parser.parse(SQL).getStructure());
        assertEquals("TABLE_CONFLICT", readiness.check(APP, Collections.singletonList(SQL)).getTables().get(0).getStatus());
    }

    /**
     * 检查期间受理并完成的操作仍通过前后总数变化发现。
     */
    @Test
    void concurrentAcceptanceInvalidatesCheck() {
        ownedTable();
        when(repository.checkContext(APP)).thenReturn(new CheckContext(resource, 0L), new CheckContext(resource, 1L));
        TableReadinessView result = readiness.check(APP, Collections.singletonList(SQL));
        assertFalse(result.isReady());
        assertEquals("CHECK_CHANGED", result.getReason());
    }

    /**
     * 读取错误不返回缺表或 ready=true。
     */
    @Test
    void metadataFailureIsNotReportedAsMissing() {
        when(schemas.readTable(1L, "orders")).thenThrow(new BizException(ResourceErrorCode.DATABASE_UNAVAILABLE));
        assertSame(ResourceErrorCode.DATABASE_UNAVAILABLE, assertThrows(BizException.class,
                () -> readiness.check(APP, Collections.singletonList(SQL))).getErrorCode());
    }

    /**
     * 目标按数据库大小写规则规范化后拒绝重复清单。
     */
    @Test
    void rejectsDuplicateCanonicalTargets() {
        assertThrows(BizException.class, () -> readiness.check(APP, Arrays.asList(SQL, SQL.replace("orders", "Orders"))));
    }

    /**
     * 放弃先于步骤准入时不发送 SQL，确定未执行后可结束并释放。
     */
    @Test
    void stoppedAdmissionNeverSendsSql() {
        doAnswer(a -> {
            TableOperation op = a.getArgument(0);
            if (op.getSteps().get(0).getStatus() == TableOperation.StepStatus.RUNNING) {
                throw new BizException(ResourceErrorCode.OPERATION_ABANDONED);
            }
            stored = copy(op);
            return null;
        }).when(repository).saveStep(any(), any());
        operations.submit(APP, "CREATE", SQL, false, Actor.system());
        verify(schemas, never()).execute(anyLong(), anyString());
        assertEquals(TableOperation.Status.FAILED, stored.getStatus());
        assertEquals("OPERATION_ABANDONED", stored.getErrorCode());
        assertNull(resource.getActiveOperationId());
    }

    /**
     * DROP 已确认后下一步被放弃，不能再发送 CREATE，目录保留缺表事实。
     */
    @Test
    void abandonBetweenRebuildStepsStopsCreate() {
        ownedTable();
        doAnswer(a -> {
            TableOperation op = a.getArgument(0);
            TableBinding.Status state = a.getArgument(1);
            if (op.getSteps().get(1).getStatus() == TableOperation.StepStatus.RUNNING) {
                throw new BizException(ResourceErrorCode.OPERATION_ABANDONED);
            }
            stored = copy(op);
            if (state == TableBinding.Status.DROPPED) {
                bindings.get("orders").dropped();
            }
            return null;
        }).when(repository).saveStep(any(), any());
        operations.submit(APP, "REBUILD", SQL, true, Actor.system());
        verify(schemas).execute(1L, "DROP TABLE `orders`");
        verify(schemas, never()).execute(1L, SQL);
        assertEquals(TableOperation.StepStatus.SUCCEEDED, stored.getSteps().get(0).getStatus());
        assertEquals(TableBinding.Status.DROPPED, bindings.get("orders").getStatus());
        assertNull(resource.getActiveOperationId());
    }

}
