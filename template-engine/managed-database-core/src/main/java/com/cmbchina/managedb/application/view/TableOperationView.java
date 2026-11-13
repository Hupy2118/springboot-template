package com.cmbchina.managedb.application.view;

import com.cmbchina.managedb.domain.OperationAbandonRequest;
import com.cmbchina.managedb.domain.TableOperation;
import lombok.Getter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 操作结果投影不暴露可修改聚合或数据库内部主键类型。
 */
@Getter
public final class TableOperationView {
    private final String operationId;
    private final String mode;
    private final String tableName;
    private final String status;
    private final String creator;
    private final Instant createdAt;
    private final List<String> tableNames;
    private final Result executionResult;

    /**
     * 从持久化恢复的领域记录创建详情。
     */
    public TableOperationView(TableOperation op) {
        tableNames = op.getTableNames();
        operationId = Long.toString(op.getId());
        mode = op.getMode().name();
        tableName = op.getTableName();
        status = op.getStatus().name();
        // 展示提交操作时的最终用户显示名；未登录保持 null，不落占位串。
        creator = op.getCreator();
        createdAt = op.getCreatedAt();
        executionResult = new Result(op);
    }

    @Getter
    public static final class StepView {
        private final int sequence;
        private final String tableName;
        private final String errorCode;
        private final String errorSummary;
        private final TableOperation.StepStatus status;
        private final Instant startedAt;
        private final Instant finishedAt;
        private final Map<String, Object> verification;

        /**
         * 从冻结目标按步骤序号派生表名。
         */
        private StepView(TableOperation.Step step, String name) {
            sequence = step.getSequence();
            tableName = name;
            status = step.getStatus();
            startedAt = step.getStartedAt();
            finishedAt = step.getFinishedAt();
            errorCode = step.getErrorCode();
            errorSummary = step.getErrorSummary();
            verification = step.getVerification();
        }
    }

    /**
     * 仅投影新增表名，持久化步骤仍沿用原有格式。
     */
    private static List<StepView> projectSteps(TableOperation op) {
        List<StepView> result = new ArrayList<>();
        for (TableOperation.Step step : op.getSteps()) {
            result.add(new StepView(step,
                    op.getMode() == TableOperation.Mode.CREATE_BATCH ? op.getTableNames().get(step.getSequence() - 1) : op.getTableName()));
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * 展示既有执行证据，步骤没有公开 setter。
     */
    @Getter
    public static final class Result {
        private final Instant startedAt, finishedAt;
        private final String errorCode, errorSummary;
        private final List<StepView> steps;
        private final OperationAbandonRequest abandonRequest;
        private final Map<String, Object> manualResolution;
        private final String recoveryState;

        /**
         * 固定本次查询的历史结果。
         */
        private Result(TableOperation op) {
            startedAt = op.getStartedAt();
            finishedAt = op.getFinishedAt();
            errorCode = op.getErrorCode();
            errorSummary = op.getErrorSummary();
            steps = projectSteps(op);
            abandonRequest = op.getAbandonRequest();
            // 数据库操作账号仅留管理审计，产品详情不返回内部账号。
            Map<String, Object> visible = new LinkedHashMap<>(op.getManualResolution());
            visible.remove("databaseActor");
            manualResolution = Collections.unmodifiableMap(visible);
            recoveryState = op.recoveryState();
        }
    }
}
