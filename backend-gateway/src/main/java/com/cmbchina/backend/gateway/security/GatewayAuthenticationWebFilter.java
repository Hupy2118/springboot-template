package com.cmbchina.backend.gateway.security;

import com.cmbchina.backend.common.security.XcodePrincipal;
import com.cmbchina.backend.gateway.config.GatewaySecurityProperties;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** 使用当前认证适配器保护公开 Gateway 请求并写入统一 Principal 属性。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class GatewayAuthenticationWebFilter implements WebFilter {
    public static final String PRINCIPAL_ATTRIBUTE = "xcode.principal";
    private final GatewaySecurityProperties properties;
    private final ObjectProvider<PublicAuthenticationAdapter> adapters;

    /** 注入认证配置和当前环境提供的认证适配器。 */
    public GatewayAuthenticationWebFilter(GatewaySecurityProperties properties,
                                          ObjectProvider<PublicAuthenticationAdapter> adapters) {
        this.properties = properties;
        this.adapters = adapters;
    }

    /** 放行显式公开路径，其余请求必须由认证适配器恢复用户身份。 */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (properties.isPublicPath(path)
                || HttpMethod.OPTIONS.equals(exchange.getRequest().getMethod())) {
            return chain.filter(exchange);
        }
        PublicAuthenticationAdapter adapter = adapters.orderedStream().findFirst().orElse(null);
        if (adapter == null) {
            return unauthorized(exchange);
        }
        String token = bearer(exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        Mono<XcodePrincipal> authentication = adapter.authenticate(exchange, token)
                .onErrorResume(exception -> Mono.empty());
        return authentication
                .flatMap(principal -> authenticated(exchange, chain, principal))
                .switchIfEmpty(Mono.defer(() -> unauthorized(exchange)));
    }

    /** 保存可信 Principal 后继续执行过滤链。 */
    private Mono<Void> authenticated(ServerWebExchange exchange, WebFilterChain chain,
                                     XcodePrincipal principal) {
        exchange.getAttributes().put(PRINCIPAL_ATTRIBUTE, principal);
        return chain.filter(exchange);
    }

    /** 从标准 Authorization Header 中提取 Bearer 令牌。 */
    public static String bearer(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        String value = authorization.substring("Bearer ".length()).trim();
        return value.isEmpty() ? null : value;
    }

    /** 返回不泄露认证实现细节的稳定错误。 */
    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        byte[] body = "{\"code\":\"GATEWAY_AUTH_REQUIRED\",\"message\":\"请先完成登录。\"}"
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse()
                .bufferFactory().wrap(body)));
    }
}
