package com.cmbchina.managedb.infrastructure.persistence;

import com.cmb.bee.commons.exceptions.BizException;
import com.cmbchina.managedb.domain.OperationAbandonRequest;
import com.cmbchina.managedb.domain.ResourceErrorCode;
import com.cmbchina.managedb.domain.TableOperation;
import com.cmbchina.managedb.infrastructure.persistence.po.TableOperationPo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON 编解码留在基础设施，不向领域对象添加 Jackson 注解。
 */
@RequiredArgsConstructor
public final class TableOperationCodec {
    private final ObjectMapper mapper;

    /**
     * 保存用户原 SQL、服务 DROP 和已校验意图。
     */
    public String content(TableOperation op) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("tableNames", op.getTableNames());
        json.put("allowDataLoss", op.getMode() != TableOperation.Mode.CREATE && op.getMode() != TableOperation.Mode.CREATE_BATCH);
        json.put("existingSatisfied", op.isExistingSatisfied());
        List<String> sqls = new ArrayList<>();
        if (op.getMode() == TableOperation.Mode.CREATE_BATCH) {
            sqls.addAll(op.getBatchSqls());
            json.put("batchSatisfied", op.getBatchSatisfied());
        } else if (op.getMode() == TableOperation.Mode.DROP || op.getMode() == TableOperation.Mode.RELEASE) {
            for (String name : op.getTableNames()) {
                sqls.add(dropSql(name));
            }
        } else {
            if (op.getMode() == TableOperation.Mode.REBUILD) {
                sqls.add(dropSql(op.getTableName()));
            }
            sqls.add(op.getCreateSql());
        }
        json.put("sqls", sqls);
        json.put("sqls", sqls);
        return encode(json, "codec.content", op.getId());
    }

    /**
     * 操作类型与存储动作一一对应，未知动作不能误当成重建。
     */
    public static String actionType(TableOperation.Mode mode) {
        switch (mode) {
            case CREATE_BATCH:
                return "CREATE_TABLE_BATCH";
            case CREATE:
                return "CREATE_TABLE";
            case REBUILD:
                return "REBUILD_TABLE";
            case DROP:
                return "DROP_TABLE";
            case RELEASE:
                return "RELEASE_DATASOURCE";
            default:
                throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
        }
    }

    /**
     * 只读取当前明确支持的动作类型。
     */
    private static TableOperation.Mode mode(String action) {
        switch (action) {
            case "CREATE_TABLE_BATCH":
                return TableOperation.Mode.CREATE_BATCH;
            case "CREATE_TABLE":
                return TableOperation.Mode.CREATE;
            case "REBUILD_TABLE":
                return TableOperation.Mode.REBUILD;
            case "DROP_TABLE":
                return TableOperation.Mode.DROP;
            case "RELEASE_DATASOURCE":
                return TableOperation.Mode.RELEASE;
            default:
                throw new BizException(ResourceErrorCode.INTERNAL_ERROR);
        }
    }

    /**
     * 精确引用单表标识符，不能拼接未校验的任意脚本。
     */
    public static String dropSql(String tableName) {
        return "DROP TABLE `" + tableName.replace("`", "``") + "`";
    }

    /**
     * 时间转换为 UTC 文本，无需领域依赖 JSON 模块。
     */
    public String result(TableOperation op) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("startedAt", time(op.getStartedAt()));
        json.put("finishedAt", time(op.getFinishedAt()));
        json.put("errorCode", op.getErrorCode());
        json.put("errorSummary", op.getErrorSummary());
        List<Map<String, Object>> steps = new ArrayList<>();
        for (TableOperation.Step step : op.getSteps()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("sequence", step.getSequence());
            entry.put("status", step.getStatus().name());
            entry.put("startedAt", time(step.getStartedAt()));
            entry.put("finishedAt", time(step.getFinishedAt()));
            entry.put("errorCode", step.getErrorCode());
            entry.put("errorSummary", step.getErrorSummary());
            entry.put("verification", step.getVerification());
            steps.add(entry);
        }
        json.put("steps", steps);
        if (op.getAbandonRequest() != null) {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("requestedAt", time(op.getAbandonRequest().getRequestedAt()));
            request.put("requestedBy", op.getAbandonRequest().getRequestedBy());
            request.put("reason", op.getAbandonRequest().getReason());
            json.put("abandonRequest", request);
        }
        if (!op.getManualResolution().isEmpty()) {
            json.put("manualResolution", op.getManualResolution());
        }
        return encode(json, "codec.result", op.getId());
    }

    /**
     * 只恢复保存的步骤，不通过新建工厂重置状态。
     */
    public TableOperation restore(TableOperationPo row) {
        String stage = "codec.readContent";
        try {
            JsonNode content = mapper.readTree(row.getExecutionJson());
            stage = "codec.readResult";
            JsonNode result = mapper.readTree(row.getExecutionResult());
            stage = "codec.restoreSteps";
            List<TableOperation.Step> steps = new ArrayList<>();
            for (JsonNode step : result.path("steps")) {
                Map<String, Object> verification = mapper.convertValue(step.path("verification"), Map.class);
                steps.add(TableOperation.Step.restore(step.path("sequence").asInt(), TableOperation.StepStatus.valueOf(step.path("status").asText()),
                        instant(step.get("startedAt")), instant(step.get("finishedAt")), string(step.get("errorCode")),
                        string(step.get("errorSummary")), verification));
            }
            stage = "codec.resolveMode";
            JsonNode sqls = content.path("sqls");
            TableOperation.Mode mode = mode(row.getActionType());
            stage = "codec.restoreAudit";
            JsonNode request = result.path("abandonRequest");
            OperationAbandonRequest abandon = request.isObject() ?
                    new OperationAbandonRequest(instant(request.get("requestedAt")), string(request.get("requestedBy")),
                            string(request.get("reason"))) : null;
            Map<String, Object> resolution =
                    result.path("manualResolution").isObject() ? mapper.convertValue(result.path("manualResolution"), Map.class) :
                            Collections.emptyMap();
            if (mode == TableOperation.Mode.CREATE_BATCH) {
                stage = "codec.restoreBatch";
                List<String> names = new ArrayList<>(), originals = new ArrayList<>();
                List<Boolean> satisfied = new ArrayList<>();
                for (JsonNode name : content.path("tableNames")) {
                    names.add(name.asText());
                }
                for (JsonNode sql : sqls) {
                    originals.add(sql.asText());
                }
                for (JsonNode flag : content.path("batchSatisfied")) {
                    if (!flag.isBoolean()) {
                        throw new IllegalArgumentException();
                    }
                    satisfied.add(flag.booleanValue());
                }
                if (!content.path("allowDataLoss").isBoolean() || content.path("allowDataLoss").asBoolean()) {
                    throw new IllegalArgumentException();
                }
                return TableOperation.restoreBatch(row.getId(), row.getDatasourceKey(), row.getDatasourceId(), names, originals, satisfied,
                        row.getCreatedBy(), row.getCreator(), row.getCreatedAt(),
                        TableOperation.Status.valueOf(row.getStatus()), steps, instant(result.get("startedAt")), instant(result.get("finishedAt")),
                        string(result.get("errorCode")), string(result.get("errorSummary")), abandon, resolution);
            }
            if (mode == TableOperation.Mode.DROP || mode == TableOperation.Mode.RELEASE) {
                stage = "codec.restoreCleanup";
                List<String> names = new ArrayList<>();
                for (JsonNode name : content.path("tableNames")) {
                    names.add(name.asText());
                }
                return TableOperation.restoreCleanup(row.getId(), row.getDatasourceKey(), row.getDatasourceId(), mode, names,
                        row.getCreatedBy(), row.getCreator(), row.getCreatedAt(),
                        TableOperation.Status.valueOf(row.getStatus()), steps, instant(result.get("startedAt")), instant(result.get("finishedAt")),
                        string(result.get("errorCode")), string(result.get("errorSummary")), abandon, resolution);
            }
            stage = "codec.restoreSingle";
            return TableOperation.restore(row.getId(), row.getDatasourceKey(), row.getDatasourceId(), content.path("tableNames").get(0).asText(),
                    sqls.get(sqls.size() - 1).asText(), mode, content.path("existingSatisfied").asBoolean(),
                    row.getCreatedBy(), row.getCreator(), row.getCreatedAt(),
                    TableOperation.Status.valueOf(row.getStatus()), steps, instant(result.get("startedAt")), instant(result.get("finishedAt")),
                    string(result.get("errorCode")), string(result.get("errorSummary")), abandon, resolution);
        } catch (Exception e) {
            PersistenceDiagnostics.failure(stage, row == null ? null : row.getId(), e);
            throw new BizException(ResourceErrorCode.INTERNAL_ERROR);
        }
    }

    /**
     * 限制持久化结果大小，异常不回显内容。
     */
    private String encode(Object value, String stage, long operationId) {
        try {
            String json = mapper.writeValueAsString(value);
            if (json.getBytes(StandardCharsets.UTF_8).length > 262144) {
                throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
            }
            return json;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            PersistenceDiagnostics.failure(stage, operationId, e);
            throw new BizException(ResourceErrorCode.INTERNAL_ERROR);
        }
    }

    /**
     * 输出可空时间。
     */
    private static String time(Instant value) {
        return value == null ? null : value.toString();
    }

    /**
     * 读取可空文本。
     */
    private static String string(JsonNode value) {
        return value == null || value.isNull() ? null : value.asText();
    }

    /**
     * 读取可空时间。
     */
    private static Instant instant(JsonNode value) {
        String text = string(value);
        return text == null ? null : Instant.parse(text);
    }
}
