package com.cmbchina.backend.gateway.agent;

/** 表示 Agent 准入策略服务返回的固定治理结果。 */
public class AgentPolicyDecision {
    private boolean allowed;
    private boolean frozen;
    private String reason;

    /** 提供 JSON 反序列化所需的空构造方法。 */
    public AgentPolicyDecision() { }

    /** 创建完整的 Agent 准入决策。 */
    public AgentPolicyDecision(boolean allowed, boolean frozen, String reason) {
        this.allowed = allowed;
        this.frozen = frozen;
        this.reason = reason;
    }

    /** 返回是否允许访问。 */
    public boolean isAllowed() { return allowed; }
    /** 设置是否允许访问。 */
    public void setAllowed(boolean allowed) { this.allowed = allowed; }
    /** 返回 Agent 是否被冻结。 */
    public boolean isFrozen() { return frozen; }
    /** 设置 Agent 是否被冻结。 */
    public void setFrozen(boolean frozen) { this.frozen = frozen; }
    /** 返回拒绝原因。 */
    public String getReason() { return reason; }
    /** 设置拒绝原因。 */
    public void setReason(String reason) { this.reason = reason; }
}
