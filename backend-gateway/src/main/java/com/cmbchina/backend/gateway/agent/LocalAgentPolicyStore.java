package com.cmbchina.backend.gateway.agent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 在 local Profile 中维护可随调试端点修改的 Agent 准入状态。 */
@Component
@Profile("local")
@ConditionalOnProperty(prefix = "gateway.agent", name = "policy-mode", havingValue = "local")
public class LocalAgentPolicyStore {
    private final Set<String> allowedAgentIds = ConcurrentHashMap.newKeySet();
    private final Set<String> frozenAgentIds = ConcurrentHashMap.newKeySet();

    /** 使用本地配置初始化允许和冻结集合。 */
    public LocalAgentPolicyStore(AgentGatewayProperties properties) {
        if (properties.getAllowedAgentIds() != null) {
            allowedAgentIds.addAll(properties.getAllowedAgentIds());
        }
        if (properties.getFrozenAgentIds() != null) {
            frozenAgentIds.addAll(properties.getFrozenAgentIds());
        }
    }

    /** 计算目标 Agent 的本地准入结果。 */
    public AgentPolicyDecision evaluate(String agentId) {
        boolean frozen = frozenAgentIds.contains(agentId);
        boolean allowed = allowedAgentIds.contains(agentId);
        return new AgentPolicyDecision(allowed && !frozen, frozen,
                frozen ? "AGENT_FROZEN" : (allowed ? null : "AGENT_NOT_ALLOWED"));
    }

    /** 把 Agent 加入本地允许集合。 */
    public void allow(String agentId) { allowedAgentIds.add(agentId); }
    /** 把 Agent 移出本地允许集合。 */
    public void deny(String agentId) { allowedAgentIds.remove(agentId); }
    /** 冻结本地 Agent。 */
    public void freeze(String agentId) { frozenAgentIds.add(agentId); }
    /** 解冻本地 Agent。 */
    public void unfreeze(String agentId) { frozenAgentIds.remove(agentId); }

    /** 返回用于本地调试页面展示的只读状态快照。 */
    public Map<String, Set<String>> snapshot() {
        Map<String, Set<String>> state = new LinkedHashMap<>();
        state.put("allowedAgentIds", Collections.unmodifiableSet(new LinkedHashSet<>(allowedAgentIds)));
        state.put("frozenAgentIds", Collections.unmodifiableSet(new LinkedHashSet<>(frozenAgentIds)));
        return state;
    }
}
