package com.cmbchina.managedb.domain;

import com.cmb.bee.commons.exceptions.BizException;
import lombok.Getter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次按钮操作及逐步执行事实，不能把未知或失败记录重置后重跑。
 */
@Getter
public final class TableOperation {
    public enum Mode {CREATE_BATCH, CREATE, REBUILD, DROP, RELEASE}

    public enum Status {RUNNING, SUCCEEDED, FAILED, UNKNOWN}

    public enum StepStatus {PENDING, RUNNING, SUCCEEDED, FAILED, UNKNOWN}

    private final long id;
    private final long datasourceId;
    private final String datasourceKey;
    private final String tableName;
    private final String createSql;
    private final String createdBy;
    private final String creator;
    private final Mode mode;
    private final List<String> tableNames;
    private final boolean existingSatisfied;
    private List<String> batchSqls = Collections.emptyList();
    private List<Boolean> batchSatisfied = Collections.emptyList();
    private final Instant createdAt;
    private final List<Step> steps;
    private Status status;
    private Instant startedAt;
    private Instant finishedAt;
    private String errorCode;
    private String errorSummary;
    private OperationAbandonRequest abandonRequest;
    private final Map<String, Object> manualResolution;

    /**
     * 保存新操作或仓储恢复的完整状态。
     */
    private TableOperation(long id, String datasourceKey, long datasourceId, String tableName, String createSql, String createdBy, String creator,
                           Mode mode, boolean existingSatisfied, Instant createdAt, Status status, List<Step> steps, Instant startedAt,
                           Instant finishedAt, String errorCode, String errorSummary, OperationAbandonRequest abandonRequest,
                           Map<String, Object> manualResolution, List<String> tableNames) {
        this.tableNames = Collections.unmodifiableList(new ArrayList<>(tableNames));
        this.id = id;
        this.datasourceKey = datasourceKey;
        this.datasourceId = datasourceId;
        this.tableName = tableName;
        this.createSql = createSql;
        this.createdBy = createdBy;
        this.creator = creator;
        this.mode = mode;
        this.existingSatisfied = existingSatisfied;
        this.createdAt = createdAt;
        this.status = status;
        this.steps = new ArrayList<>(steps);
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.errorCode = errorCode;
        this.errorSummary = errorSummary;
        this.abandonRequest = abandonRequest;
        this.manualResolution = Collections.unmodifiableMap(new LinkedHashMap<>(manualResolution));
    }

    /**
     * 创建已受理操作，重建固定为删表和建表两步。
     */
    public static TableOperation start(long id, String datasourceKey, long datasourceId, String tableName, String sql, Mode mode, boolean satisfied,
                                       Actor actor) {
        if (mode != Mode.CREATE && mode != Mode.REBUILD) {
            throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
        }
        List<Step> steps = new ArrayList<>();
        if (mode == Mode.REBUILD) {
            steps.add(Step.pending(1));
        }
        steps.add(Step.pending(steps.size() + 1));
        return new TableOperation(id, datasourceKey, datasourceId, tableName, sql, actor.getUserId(), actor.getUserName(), mode, satisfied,
                Instant.now(), Status.RUNNING, steps, null, null, null, null, null, Collections.emptyMap(), Collections.singletonList(tableName));
    }

    /**
     * 仓储恢复原状态，不重新创建步骤或清除历史结果。
     */
    public static TableOperation restore(long id, String datasourceKey, long datasourceId, String tableName, String sql, Mode mode, boolean satisfied,
                                         String createdBy, String creator, Instant createdAt, Status status, List<Step> steps, Instant startedAt,
                                         Instant finishedAt, String errorCode, String errorSummary, OperationAbandonRequest abandonRequest,
                                         Map<String, Object> manualResolution) {
        return new TableOperation(id, datasourceKey, datasourceId, tableName, sql, createdBy, creator, mode, satisfied, createdAt, status, steps,
                startedAt, finishedAt, errorCode, errorSummary, abandonRequest, manualResolution, Collections.singletonList(tableName));
    }

    /**
     * 冻结精确归属清单；整份数据源资源空清单保留一个只结束资源状态的步骤。
     */
    public static TableOperation cleanup(long id, String datasourceKey, long datasourceId, Mode mode, List<String> names, Actor actor) {
        if ((mode != Mode.DROP && mode != Mode.RELEASE) || names == null || names.size() > 200 || (mode == Mode.DROP && names.size() != 1)
                || new HashSet<>(names).size() != names.size()) {
            throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
        }
        for (String name : names) {
            if (name == null || name.isEmpty() || name.length() > 64 || name.indexOf('\0') >= 0) {
                throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
            }
        }
        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < Math.max(1, names.size()); i++) {
            steps.add(Step.pending(i + 1));
        }
        return new TableOperation(id, datasourceKey, datasourceId, mode == Mode.DROP ? names.get(0) : "", "", actor.getUserId(), actor.getUserName(),
                mode, false, Instant.now(), Status.RUNNING, steps, null, null, null, null, null, Collections.emptyMap(), names);
    }

    /**
     * 恢复清理的原目标及步骤，不重新枚举资源或重放已发送 SQL。
     */
    public static TableOperation restoreCleanup(long id, String datasourceKey, long datasourceId, Mode mode, List<String> names, String createdBy,
                                                String creator, Instant createdAt, Status status, List<Step> steps, Instant startedAt,
                                                Instant finishedAt, String errorCode, String errorSummary, OperationAbandonRequest abandonRequest,
                                                Map<String, Object> manualResolution) {
        if ((mode != Mode.DROP && mode != Mode.RELEASE) || steps.size() != Math.max(1, names.size())) {
            throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
        }
        return new TableOperation(id, datasourceKey, datasourceId, mode == Mode.DROP ? names.get(0) : "", "", createdBy, creator, mode, false,
                createdAt, status, steps, startedAt, finishedAt, errorCode, errorSummary, abandonRequest, manualResolution, names);
    }

    /**
     * 创建有序批次，逐表状态与原 SQL 一并冻结。
     */
    public static TableOperation startBatch(long id, String key, long datasourceId, List<String> names, List<String> sqls, List<Boolean> satisfied,
                                            String createdBy, String creator) {
        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            steps.add(Step.pending(i + 1));
        }
        return restoreBatch(id, key, datasourceId, names, sqls, satisfied, createdBy, creator, Instant.now(), Status.RUNNING, steps,
                null, null, null, null, null, Collections.emptyMap());
    }

    /**
     * 恢复批次原始目标和证据，不重新生成步骤。
     */
    public static TableOperation restoreBatch(long id, String key, long datasourceId, List<String> names, List<String> sqls, List<Boolean> satisfied,
                                              String createdBy, String creator, Instant createdAt,
                                              Status status, List<Step> steps, Instant startedAt, Instant finishedAt, String code,
                                              String summary, OperationAbandonRequest abandon, Map<String, Object> resolution) {
        if (names == null || names.isEmpty() || names.size() > 50 || names.size() != sqls.size() || names.size() != satisfied.size()
                || names.size() != steps.size() || new HashSet<>(names).size() != names.size()) {
            throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
        }
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i) == null || names.get(i).isEmpty() || names.get(i).length() > 64 || names.get(i).indexOf('\0') >= 0 || sqls.get(i) == null
                    || sqls.get(i).trim().isEmpty() || satisfied.get(i) == null || steps.get(i).getSequence() != i + 1) {
                throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
            }
        }
        TableOperation op =
                new TableOperation(id, key, datasourceId, "", "", createdBy, creator, Mode.CREATE_BATCH, false, createdAt, status, steps,
                        startedAt, finishedAt, code, summary, abandon, resolution, names);
        op.batchSqls = Collections.unmodifiableList(new ArrayList<>(sqls));
        op.batchSatisfied = Collections.unmodifiableList(new ArrayList<>(satisfied));
        return op;
    }

    /**
     * 放弃只登记意图；重复申请保留首次审计，不擅自清占用。
     */
    public void requestAbandon(String reason, Actor actor) {
        if (abandonRequest != null) {
            return;
        }
        if (status != Status.RUNNING && status != Status.UNKNOWN) {
            throw new BizException(ResourceErrorCode.RESOURCE_BUSY);
        }
        if (reason == null || reason.trim().isEmpty() || reason.length() > 500) {
            throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
        }
        String userId = actor.getUserId();
        String requestedBy = (userId == null || userId.isEmpty()) ? "STUDIO_SERVICE" : userId;
        abandonRequest = new OperationAbandonRequest(Instant.now(), requestedBy, reason.trim());
    }

    /**
     * 工作线程写回时保留锁内读取到的放弃请求，防止旧内存快照覆盖用户意图。
     */
    public void retainAbandonRequest(TableOperation persisted) {
        abandonRequest = persisted.abandonRequest;
    }

    /**
     * 与步骤准入事务共同使用，已获准步骤仍需确认执行结果。
     */
    public void requireExecutionAllowed() {
        if (abandonRequest != null) {
            throw new BizException(ResourceErrorCode.OPERATION_ABANDONED);
        }
    }

    /**
     * 以处置事实投影状态，不将请求受理误报成已释放。
     */
    public String recoveryState() {
        if (!manualResolution.isEmpty()) {
            return "MANUALLY_ABANDONED";
        }
        if (abandonRequest == null) {
            return "NOT_REQUESTED";
        }
        if (status == Status.UNKNOWN) {
            return "MANUAL_REQUIRED";
        }
        if (status == Status.RUNNING) {
            return "STOP_REQUESTED";
        }
        if (status == Status.SUCCEEDED) {
            return "COMPLETED_BEFORE_ABANDON";
        }
        return "OPERATION_ABANDONED".equals(errorCode) ? "ABANDONED" : "ENDED";
    }

    /**
     * 发出 SQL 前登记步骤；不能跳过未成功的前序步骤。
     */
    public void markRunning(int index) {
        requireRunning();
        for (int i = 0; i < index; i++) {
            if (steps.get(i).status != StepStatus.SUCCEEDED) {
                throw new BizException(ResourceErrorCode.RESOURCE_BUSY);
            }
        }
        Step step = steps.get(index);
        if (step.status != StepStatus.PENDING) {
            throw new BizException(ResourceErrorCode.RESOURCE_BUSY);
        }
        step.status = StepStatus.RUNNING;
        step.startedAt = Instant.now();
        if (startedAt == null) {
            startedAt = step.startedAt;
        }
    }

    /**
     * 保存单步确定或未知证据，允许发出 SQL 前的明确失败。
     */
    public void complete(int index, StepStatus result, String code, Map<String, Object> verification) {
        requireRunning();
        Step step = steps.get(index);
        if (step.status != StepStatus.PENDING && step.status != StepStatus.RUNNING) {
            throw new BizException(ResourceErrorCode.RESOURCE_BUSY);
        }
        if (result == StepStatus.PENDING || result == StepStatus.RUNNING) {
            throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
        }
        if (step.startedAt == null) {
            step.startedAt = Instant.now();
        }
        if (startedAt == null) {
            startedAt = step.startedAt;
        }
        step.status = result;
        step.finishedAt = result == StepStatus.UNKNOWN ? null : Instant.now();
        step.errorCode = code;
        step.errorSummary = code == null ? null : "本步骤未完成，请检查执行结果";
        step.verification = Collections.unmodifiableMap(new LinkedHashMap<>(verification));
    }

    /**
     * 只有所有步骤成功才能宣告成功，未知不记录确定结束时间。
     */
    public void finish(Status result, String code) {
        requireRunning();
        if (result == Status.RUNNING) {
            throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
        }
        if (result == Status.SUCCEEDED && steps.stream().anyMatch(s -> s.status != StepStatus.SUCCEEDED)) {
            throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
        }
        status = result;
        errorCode = code;
        errorSummary = code == null ? null : "操作未完成，请根据逐步结果处理，禁止自动重放";
        finishedAt = result == Status.UNKNOWN ? null : Instant.now();
    }

    /**
     * 阻止任何终态被隐式恢复为执行中。
     */
    private void requireRunning() {
        if (status != Status.RUNNING) {
            throw new BizException(ResourceErrorCode.RESOURCE_BUSY);
        }
    }

    /**
     * 返回步骤只读集合，步骤对象没有公开修改入口。
     */
    public List<Step> getSteps() {
        return Collections.unmodifiableList(steps);
    }

    /**
     * 单步记录，仅由所属操作维护。
     */
    @Getter
    public static final class Step {
        private final int sequence;
        private StepStatus status;
        private Instant startedAt, finishedAt;
        private String errorCode, errorSummary;
        private Map<String, Object> verification;

        /**
         * 私有构造避免外部绕过操作状态规则。
         */
        private Step(int sequence, StepStatus status, Instant startedAt, Instant finishedAt, String code, String summary,
                     Map<String, Object> verification) {
            this.sequence = sequence;
            this.status = status;
            this.startedAt = startedAt;
            this.finishedAt = finishedAt;
            this.errorCode = code;
            this.errorSummary = summary;
            this.verification = Collections.unmodifiableMap(new LinkedHashMap<>(verification));
        }

        /**
         * 新建尚未执行步骤。
         */
        private static Step pending(int sequence) {
            return new Step(sequence, StepStatus.PENDING, null, null, null, null, Collections.emptyMap());
        }

        /**
         * 恢复落库证据，不能重置历史步骤。
         */
        public static Step restore(int sequence, StepStatus status, Instant start, Instant finish, String code, String summary,
                                   Map<String, Object> verification) {
            return new Step(sequence, status, start, finish, code, summary, verification);
        }
    }
}
