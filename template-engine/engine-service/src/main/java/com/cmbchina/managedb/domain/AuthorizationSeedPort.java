package com.cmbchina.managedb.domain;

/** 固定挂载上受限的首次权限初始化能力。 */
public interface AuthorizationSeedPort {
    /** 只读预检四张表的结构和事务能力。 */
    void preflight(long datasourceId, AuthorizationSeedPayload payload);

    /** 在单事务内插入、回读并提交，明确区分失败与未知。 */
    DataOperation.Result execute(long datasourceId, AuthorizationSeedPayload payload);
}
