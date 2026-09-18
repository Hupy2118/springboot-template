package com.cmbchina.backend.gateway.agent;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 保存 Agent 准入治理和本地调试策略。 */
@ConfigurationProperties(prefix = "gateway.agent")
public class AgentGatewayProperties {
    private boolean enabled;
    private String policyMode = "remote";
    private String policyBaseUrl;
    private String policyPath = "/internal/gateway/agent-policy";
    private long policyTimeoutMillis = 2000;
    private boolean failClosed = true;
    private List<String> allowedAgentIds = new ArrayList<>();
    private List<String> frozenAgentIds = new ArrayList<>();

    /** 返回是否启用 Agent 准入治理。 */
    public boolean isEnabled() { return enabled; }
    /** 设置是否启用 Agent 准入治理。 */
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    /** 返回策略来源模式。 */
    public String getPolicyMode() { return policyMode; }
    /** 设置策略来源模式。 */
    public void setPolicyMode(String policyMode) { this.policyMode = policyMode; }
    /** 返回远程策略服务地址。 */
    public String getPolicyBaseUrl() { return policyBaseUrl; }
    /** 设置远程策略服务地址。 */
    public void setPolicyBaseUrl(String policyBaseUrl) { this.policyBaseUrl = policyBaseUrl; }
    /** 返回远程策略查询路径。 */
    public String getPolicyPath() { return policyPath; }
    /** 设置远程策略查询路径。 */
    public void setPolicyPath(String policyPath) { this.policyPath = policyPath; }
    /** 返回远程策略超时毫秒数。 */
    public long getPolicyTimeoutMillis() { return policyTimeoutMillis; }
    /** 设置远程策略超时毫秒数。 */
    public void setPolicyTimeoutMillis(long policyTimeoutMillis) { this.policyTimeoutMillis = policyTimeoutMillis; }
    /** 返回策略服务异常时是否拒绝请求。 */
    public boolean isFailClosed() { return failClosed; }
    /** 设置策略服务异常时是否拒绝请求。 */
    public void setFailClosed(boolean failClosed) { this.failClosed = failClosed; }
    /** 返回本地允许访问的 Agent 标识。 */
    public List<String> getAllowedAgentIds() { return allowedAgentIds; }
    /** 设置本地允许访问的 Agent 标识。 */
    public void setAllowedAgentIds(List<String> allowedAgentIds) { this.allowedAgentIds = allowedAgentIds; }
    /** 返回本地冻结的 Agent 标识。 */
    public List<String> getFrozenAgentIds() { return frozenAgentIds; }
    /** 设置本地冻结的 Agent 标识。 */
    public void setFrozenAgentIds(List<String> frozenAgentIds) { this.frozenAgentIds = frozenAgentIds; }
}
