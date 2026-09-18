package com.cmbchina.backend.gateway.agent;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

import com.cmbchina.backend.common.security.XcodePrincipal;
import com.cmbchina.backend.gateway.security.GatewayAuthenticationWebFilter;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** 只对 Agent Route 执行固定的可用性、冻结和用户准入检查。 */
@Component
@EnableConfigurationProperties(AgentGatewayProperties.class)
public class AgentAdmissionGlobalFilter implements GlobalFilter, Ordered {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentAdmissionGlobalFilter.class);
    private final AgentGatewayProperties properties;
    private final ObjectProvider<AgentPolicyClient> policyClients;

    /** 注入 Agent 治理配置和当前策略来源。 */
    public AgentAdmissionGlobalFilter(AgentGatewayProperties properties,
                                      ObjectProvider<AgentPolicyClient> policyClients) {
        this.properties = properties;
        this.policyClients = policyClients;
    }

    /** 对标记为 agent 的路由执行 fail-closed 准入检查。 */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
        if (!properties.isEnabled() || !isAgentRoute(route)) {
            return chain.filter(exchange);
        }
        XcodePrincipal principal = exchange.getAttribute(GatewayAuthenticationWebFilter.PRINCIPAL_ATTRIBUTE);
        String agentId = resolveAgentId(exchange, route);
        if (principal == null) {
            return reject(exchange, HttpStatus.UNAUTHORIZED, "AGENT_AUTH_REQUIRED", "Agent 请求缺少可信用户身份。");
        }
        if (agentId == null) {
            return reject(exchange, HttpStatus.BAD_REQUEST, "AGENT_ID_REQUIRED", "Agent 请求缺少有效 agentId。");
        }
        AgentPolicyClient client = policyClients.orderedStream().findFirst().orElse(null);
        if (client == null) {
            return reject(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                    "AGENT_POLICY_UNAVAILABLE", "Agent 准入策略不可用。");
        }
        Mono<AgentPolicyDecision> evaluation = client.evaluate(principal.getName(), agentId)
                .onErrorResume(exception -> policyFailureDecision(exception));
        return evaluation.flatMap(decision -> applyDecision(exchange, chain, decision));
    }

    /** 判断当前路由是否由 Agent Runtime 拥有。 */
    private boolean isAgentRoute(Route route) {
        return route != null && "agent".equals(String.valueOf(route.getMetadata().get("xcode.route-kind")));
    }

    /** 优先从受管路由元数据读取 Agent ID，再读取受限请求参数。 */
    private String resolveAgentId(ServerWebExchange exchange, Route route) {
        Object configured = route.getMetadata().get("xcode.agent-id");
        String candidate = configured == null
                ? exchange.getRequest().getQueryParams().getFirst("agentId") : String.valueOf(configured);
        if (candidate == null) {
            candidate = exchange.getRequest().getHeaders().getFirst("Xcode-Agent-Id");
        }
        return candidate != null && candidate.matches("[A-Za-z0-9._:-]{1,128}") ? candidate : null;
    }

    /** 根据策略决策放行或返回稳定的冻结/拒绝错误。 */
    private Mono<Void> applyDecision(ServerWebExchange exchange, GatewayFilterChain chain,
                                     AgentPolicyDecision decision) {
        if (decision == null) {
            return reject(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                    "AGENT_POLICY_INVALID", "Agent 准入策略响应无效。");
        }
        if ("AGENT_POLICY_UNAVAILABLE".equals(decision.getReason())) {
            return reject(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                    "AGENT_POLICY_UNAVAILABLE", "Agent 准入策略暂不可用。");
        }
        if (decision.isFrozen()) {
            return reject(exchange, HttpStatus.LOCKED, "AGENT_FROZEN", "Agent 当前已冻结。");
        }
        if (!decision.isAllowed()) {
            return reject(exchange, HttpStatus.FORBIDDEN, "AGENT_ACCESS_DENIED", "当前用户不能访问该 Agent。");
        }
        return chain.filter(exchange);
    }

    /** 在策略依赖异常时按配置执行安全失败或受控放行。 */
    private Mono<AgentPolicyDecision> policyFailureDecision(Throwable exception) {
        LOGGER.error("Agent 准入策略检查失败。", exception);
        return Mono.just(properties.isFailClosed()
                ? new AgentPolicyDecision(false, false, "AGENT_POLICY_UNAVAILABLE")
                : new AgentPolicyDecision(true, false, "AGENT_POLICY_BYPASSED"));
    }

    /** 写出不包含内部异常细节的结构化 Gateway 错误。 */
    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String code, String message) {
        byte[] body = ("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse()
                .bufferFactory().wrap(body)));
    }

    /** 确保 Agent 准入先于内部身份签发执行。 */
    @Override
    public int getOrder() { return -100; }
}
