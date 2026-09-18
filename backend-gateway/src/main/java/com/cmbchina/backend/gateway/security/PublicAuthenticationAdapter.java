package com.cmbchina.backend.gateway.security;

import com.cmbchina.backend.common.security.XcodePrincipal;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** 定义公开凭据到可信用户身份的认证适配器合同。 */
public interface PublicAuthenticationAdapter {
    /** 校验公开凭据并返回可信用户；无效凭据返回空结果。 */
    Mono<XcodePrincipal> authenticate(ServerWebExchange exchange, String bearerToken);
}
