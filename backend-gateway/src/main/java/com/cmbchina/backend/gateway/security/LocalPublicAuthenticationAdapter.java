package com.cmbchina.backend.gateway.security;

import com.cmbchina.backend.common.security.XcodePrincipal;
import com.cmbchina.backend.gateway.mock.MockAuthenticationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** 将 local Profile 的内存令牌转换为统一可信用户身份。 */
@Component
@Profile("local")
@ConditionalOnProperty(prefix = "gateway.security", name = "mode", havingValue = "local")
public class LocalPublicAuthenticationAdapter implements PublicAuthenticationAdapter {
    private final MockAuthenticationService authenticationService;

    /** 注入仅本地可用的认证服务。 */
    public LocalPublicAuthenticationAdapter(MockAuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    /** 校验内存令牌并返回固定本地用户。 */
    @Override
    public Mono<XcodePrincipal> authenticate(ServerWebExchange exchange, String bearerToken) {
        return Mono.justOrEmpty(authenticationService.authenticate(bearerToken));
    }
}
