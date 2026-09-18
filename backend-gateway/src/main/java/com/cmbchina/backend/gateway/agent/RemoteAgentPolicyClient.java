package com.cmbchina.backend.gateway.agent;

import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** 通过响应式内部接口查询生产环境的 Agent 准入策略。 */
@Component
@ConditionalOnProperty(prefix = "gateway.agent", name = "policy-mode", havingValue = "remote", matchIfMissing = true)
public class RemoteAgentPolicyClient implements AgentPolicyClient {
    private final WebClient webClient;
    private final AgentGatewayProperties properties;

    /** 创建不阻塞 Netty 线程的策略客户端。 */
    public RemoteAgentPolicyClient(WebClient.Builder builder, AgentGatewayProperties properties) {
        this.webClient = builder.build();
        this.properties = properties;
    }

    /** 查询权威策略服务并施加严格超时。 */
    @Override
    public Mono<AgentPolicyDecision> evaluate(String userId, String agentId) {
        if (properties.getPolicyBaseUrl() == null || properties.getPolicyBaseUrl().trim().isEmpty()) {
            return Mono.error(new IllegalStateException("gateway.agent.policy-base-url 未配置。"));
        }
        return webClient.get()
                .uri(properties.getPolicyBaseUrl() + properties.getPolicyPath()
                        + "?userId={userId}&agentId={agentId}", userId, agentId)
                .retrieve()
                .bodyToMono(AgentPolicyDecision.class)
                .timeout(Duration.ofMillis(properties.getPolicyTimeoutMillis()));
    }
}
