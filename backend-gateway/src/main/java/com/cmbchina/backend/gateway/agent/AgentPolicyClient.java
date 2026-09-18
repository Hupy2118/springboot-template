package com.cmbchina.backend.gateway.agent;

import reactor.core.publisher.Mono;

/** 定义 Gateway 查询固定 Agent 准入策略的响应式合同。 */
public interface AgentPolicyClient {
    /** 查询当前用户访问目标 Agent 的准入结果。 */
    Mono<AgentPolicyDecision> evaluate(String userId, String agentId);
}
