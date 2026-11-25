package com.cmbchina.managedb.domain;

import com.cmb.bee.commons.exceptions.BizException;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 独立的 Seed 操作记录，公开摘要不包含原始载荷。 */
@Getter
@AllArgsConstructor
public final class DataOperation {
    public enum Status { RUNNING, SUCCEEDED, FAILED, UNKNOWN }

    private final long id;
    private final String datasourceKey;
    private final long datasourceId;
    private final String fingerprint;
    private final Result result;

    /** 同键内容冲突必须明确拒绝。 */
    public DataOperation requireFingerprint(String expected) {
        if (!fingerprint.equals(expected)) {
            throw new BizException(ResourceErrorCode.IDEMPOTENCY_CONFLICT);
        }
        return this;
    }

    /** 只保存可公开的结果证据与脱敏错误码。 */
    @Getter
    @RequiredArgsConstructor(access = lombok.AccessLevel.PRIVATE)
    public static final class Result {
        private final Status status;
        private final int createdCount;
        private final int existingCount;
        private final ResourceErrorCode error;

        /** 初始化未执行的受理记录。 */
        public static Result running() {
            return new Result(Status.RUNNING, 0, 0, null);
        }

        /** 仅在回读且事务提交后创建成功证据。 */
        public static Result committed(int createdCount, int existingCount) {
            long total = (long) createdCount + existingCount;
            if (createdCount < 0 || existingCount < 0 || total < AuthorizationSeedPayload.Table.values().length
                    || total > AuthorizationSeedPayload.MAX_ROWS) {
                throw new BizException(ResourceErrorCode.INTERNAL_ERROR);
            }
            return new Result(Status.SUCCEEDED, createdCount, existingCount, null);
        }

        /** 明确未执行或已回滚时记录失败。 */
        public static Result failed(ResourceErrorCode error) {
            return new Result(Status.FAILED, 0, 0, error);
        }

        /** 事务或任务状态无法确认时保留占用。 */
        public static Result unknown() {
            return new Result(Status.UNKNOWN, 0, 0, ResourceErrorCode.EXECUTION_UNKNOWN);
        }
    }
}
