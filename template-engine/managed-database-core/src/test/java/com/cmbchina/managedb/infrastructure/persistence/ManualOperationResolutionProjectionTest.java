package com.cmbchina.managedb.infrastructure.persistence;
import com.cmbchina.managedb.domain.Actor;

import com.cmbchina.managedb.application.view.TableOperationView;
import com.cmbchina.managedb.domain.TableOperation;
import com.cmbchina.managedb.infrastructure.persistence.po.TableOperationPo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 核对人工工具的结果合同能被当前Codec及详情恢复；不代替MySQL事务与过程验证。
 */
class ManualOperationResolutionProjectionTest {
    private static final String KEY = "12ab34cd56ef";
    private final ObjectMapper mapper = new ObjectMapper();
    private final TableOperationCodec codec = new TableOperationCodec(mapper);

    /**
     * 四种操作均保留目标、逐步证据、原执行时间和用户申请，详情展示人工结束状态。
     */
    @Test
    void projectsAllFourManuallyResolvedModes() throws Exception {
        String table = KEY + "_orders";
        for (TableOperation op : Arrays.asList(
                TableOperation.start(101L, KEY, 2L, table, "CREATE TABLE `" + table + "`(id bigint PRIMARY KEY)", TableOperation.Mode.CREATE, false, Actor.system()),
                TableOperation.start(101L, KEY, 2L, table, "CREATE TABLE `" + table + "`(id bigint PRIMARY KEY)", TableOperation.Mode.REBUILD, false, Actor.system()),
                TableOperation.cleanup(101L, KEY, 2L, TableOperation.Mode.DROP, Collections.singletonList(table), Actor.system()),
                TableOperation.cleanup(101L, KEY, 2L, TableOperation.Mode.RELEASE, Arrays.asList(KEY + "_child", table, KEY + "_remaining"), Actor.system()))) {
            int uncertain = op.getSteps().size() > 1 ? 1 : 0;
            if (uncertain == 1) {
                op.markRunning(0);
                op.complete(0, TableOperation.StepStatus.SUCCEEDED, null, Collections.singletonMap("sqlExecuted", true));
            }
            op.markRunning(uncertain);
            op.complete(uncertain, TableOperation.StepStatus.UNKNOWN, "EXECUTION_UNKNOWN", Collections.singletonMap("sqlExecuted", null));
            op.finish(TableOperation.Status.UNKNOWN, "EXECUTION_UNKNOWN");
            op.requestAbandon("接受原操作已发生的变更", Actor.system());
            assertProjection(op);
        }
    }

    /**
     * 空资源RELEASE仍有一个逻辑步骤，人工结束可展示但不伪造真实表或执行完成时间。
     */
    @Test
    void projectsEmptyResourceRelease() throws Exception {
        TableOperation op = TableOperation.cleanup(101L, KEY, 2L, TableOperation.Mode.RELEASE, Collections.emptyList(), Actor.system());
        op.finish(TableOperation.Status.UNKNOWN, "EXECUTION_UNKNOWN");
        TableOperation restored = assertProjection(op);
        assertTrue(restored.getTableNames().isEmpty());
        assertEquals(1, restored.getSteps().size());
        assertEquals(TableOperation.StepStatus.PENDING, restored.getSteps().get(0).getStatus());
        assertNull(restored.getStartedAt());
        assertNull(restored.getFinishedAt());
    }

    /**
     * 详情视图透出提交操作的用户显示名；未登录保持 null 而不返回占位串。
     */
    @Test
    void viewProjectsCreatorAndSystemStaysNull() {
        TableOperation withUser = TableOperation.start(102L, KEY, 2L, KEY + "_orders",
                "CREATE TABLE `" + KEY + "_orders`(id bigint PRIMARY KEY)", TableOperation.Mode.CREATE, false,
                Actor.of("123456", "张三"));
        assertEquals("张三", new TableOperationView(withUser).getCreator());

        TableOperation system = TableOperation.start(103L, KEY, 2L, KEY + "_orders",
                "CREATE TABLE `" + KEY + "_orders`(id bigint PRIMARY KEY)", TableOperation.Mode.CREATE, false,
                Actor.system());
        assertNull(new TableOperationView(system).getCreator());
    }


    /**
     * 按SQL工具约定构造管理结果样例，核对读取链路及内部数据库审计身份脱敏。
     */
    private TableOperation assertProjection(TableOperation original) throws Exception {
        ObjectNode before = (ObjectNode)mapper.readTree(codec.result(original));
        ObjectNode result = before.deepCopy();
        result.put("errorCode", "MANUALLY_ABANDONED");
        result.put("errorSummary", "操作已人工结束，请重新检查真实结构后继续操作");
        ObjectNode audit = result.putObject("manualResolution");
        audit.put("resolvedAt", "2026-10-19T08:00:00.000000Z");
        audit.put("resolvedBy", "test-operator");
        audit.put("databaseActor", "test-maintenance-account");
        audit.put("reason", "结束未确认操作");
        audit.put("evidence", "test-evidence-reference");
        audit.put("previousStatus", "UNKNOWN");
        audit.put("previousErrorCode", "EXECUTION_UNKNOWN");
        audit.put("actionType", TableOperationCodec.actionType(original.getMode()));
        audit.put("workersStopped", true);
        audit.put("sqlEnded", true);
        com.fasterxml.jackson.databind.node.ArrayNode observations = audit.putArray("tableObservations");
        for (String name : original.getTableNames()) {
            observations.addObject().put("tableName", name).put("exists", false);
        }
        audit.putArray("directoryBefore");
        TableOperationPo row = new TableOperationPo();
        row.setId(original.getId());
        row.setDatasourceKey(KEY);
        row.setDatasourceId(2L);
        row.setActionType(TableOperationCodec.actionType(original.getMode()));
        row.setStatus("FAILED");
        row.setCreatedAt(original.getCreatedAt());
        row.setExecutionJson(codec.content(original));
        row.setExecutionResult(mapper.writeValueAsString(result));
        TableOperation restored = codec.restore(row);
        assertEquals(original.getMode(), restored.getMode());
        assertEquals(original.getTableNames(), restored.getTableNames());
        assertEquals(original.getCreateSql(), restored.getCreateSql());
        assertEquals(original.getStartedAt(), restored.getStartedAt());
        assertEquals(original.getFinishedAt(), restored.getFinishedAt());
        assertEquals(TableOperation.Status.FAILED, restored.getStatus());
        JsonNode roundTrip = mapper.readTree(codec.result(restored));
        assertEquals(before.path("steps"), roundTrip.path("steps"));
        assertEquals(before.path("abandonRequest"), roundTrip.path("abandonRequest"));
        assertEquals(result.path("manualResolution"), roundTrip.path("manualResolution"));
        TableOperationView view = new TableOperationView(restored);
        assertEquals("MANUALLY_ABANDONED", view.getExecutionResult().getRecoveryState());
        assertFalse(view.getExecutionResult().getManualResolution().containsKey("databaseActor"));
        assertEquals("test-operator", view.getExecutionResult().getManualResolution().get("resolvedBy"));
        return restored;
    }
}
