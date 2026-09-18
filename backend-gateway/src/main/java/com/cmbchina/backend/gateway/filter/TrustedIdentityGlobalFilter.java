package com.cmbchina.backend.gateway.filter;

import com.cmbchina.backend.common.security.XcodePrincipal;
import com.cmbchina.backend.common.security.XcodeUserInfoTokenCodec;
import com.cmbchina.backend.gateway.config.GatewaySecurityProperties;
import com.cmbchina.backend.gateway.security.GatewayAuthenticationWebFilter;
import java.util.UUID;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

/** 清理客户端身份头，并为精确匹配的内部 Endpoint 注入可信短时身份。 */
@Component
public class TrustedIdentityGlobalFilter implements GlobalFilter, Ordered {
    private final XcodeUserInfoTokenCodec tokenCodec;
    private final GatewaySecurityProperties properties;

    /** 注入内部身份签发器和服务身份配置。 */
    public TrustedIdentityGlobalFilter(XcodeUserInfoTokenCodec tokenCodec,
                                       GatewaySecurityProperties properties) {
        this.tokenCodec = tokenCodec;
        this.properties = properties;
    }

    /** 为已登录用户签发绑定当前 Route ID 的内部身份后再转发。 */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        XcodePrincipal principal = exchange.getAttribute(GatewayAuthenticationWebFilter.PRINCIPAL_ATTRIBUTE);
        Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
        String requestId = requestId(exchange.getRequest().getHeaders().getFirst("X-Request-Id"));
        ServerHttpRequest request = exchange.getRequest().mutate().headers(headers -> {
            // 内部身份头必须先无条件清理，不能依赖路由或认证结果。
            headers.remove(HttpHeaders.AUTHORIZATION);
            headers.remove(HttpHeaders.COOKIE);
            headers.remove("Xcode-User-Info");
            headers.remove("Claw-User-Info");
            headers.remove("X-User-Id");
            headers.remove("Xcode-Endpoint-Id");
            headers.remove("Xcode-Service-Token");
            headers.set("X-Request-Id", requestId);
            if (principal != null && route != null) {
                String routeId = route.getId();
                String userInfo = tokenCodec.issue(principal, routeId, requestId,
                        properties.getInternalIdentityTtlSeconds());
                headers.set("Xcode-User-Info", userInfo);
                headers.set("Xcode-Endpoint-Id", routeId);
                headers.set("Xcode-Service-Token", properties.getServiceToken());
            }
        }).build();
        return chain.filter(exchange.mutate().request(request).build());
    }

    /** 接受格式受限的请求 ID，否则生成新值。 */
    private String requestId(String supplied) {
        return supplied != null && supplied.matches("[A-Za-z0-9_-]{1,64}")
                ? supplied : UUID.randomUUID().toString();
    }

    /** 在路由转发过滤器之前注入可信内部身份。 */
    @Override
    public int getOrder() {
        return -50;
    }
}
