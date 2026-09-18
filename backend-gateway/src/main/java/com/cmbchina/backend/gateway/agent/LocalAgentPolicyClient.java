package com.cmbchina.backend.gateway.agent;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** 使用内存状态为本地 Agent 调试提供准入决策。 */
@Component
@Profile("local")
@ConditionalOnProperty(prefix = "gateway.agent", name = "policy-mode", havingValue = "local")
public class LocalAgentPolicyClient implements AgentPolicyClient {
    private final LocalAgentPolicyStore store;

    /** 注入本地 Agent 策略状态。 */
    public LocalAgentPolicyClient(LocalAgentPolicyStore store) { this.store = store; }

    /** 返回本地配置产生的 Agent 准入结果。 */
    @Override
    public Mono<AgentPolicyDecision> evaluate(String userId, String agentId) {
        return Mono.just(store.evaluate(agentId));
    }
}
