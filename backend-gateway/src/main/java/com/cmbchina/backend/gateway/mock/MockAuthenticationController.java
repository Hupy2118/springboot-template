package com.cmbchina.backend.gateway.mock;

import com.cmbchina.backend.common.security.XcodePrincipal;
import org.springframework.http.HttpHeaders;
import com.cmbchina.backend.gateway.security.GatewayAuthenticationWebFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

/** 暴露仅限本地开发的固定用户登录、查询和退出接口。 */
@RestController
@RequestMapping("/_xcode/auth")
@Profile("local")
@ConditionalOnProperty(prefix = "gateway.security", name = "mode", havingValue = "local")
public class MockAuthenticationController {
    private final MockAuthenticationService authenticationService;

    /** 注入 Mock 会话服务。 */
    public MockAuthenticationController(MockAuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    /** 不接收用户输入，始终登录模板配置中的唯一 Mock 用户。 */
    @PostMapping("/mock/login")
    public MockAuthenticationService.LoginResult login() {
        return authenticationService.login();
    }

    /** 返回当前 Mock 会话对应的可信用户。 */
    @GetMapping("/me")
    public XcodePrincipal me(ServerWebExchange exchange) {
        return exchange.getAttribute(GatewayAuthenticationWebFilter.PRINCIPAL_ATTRIBUTE);
    }

    /** 注销当前 Mock 会话。 */
    @PostMapping("/logout")
    public void logout(ServerWebExchange exchange) {
        authenticationService.logout(GatewayAuthenticationWebFilter.bearer(
                exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION)));
    }
}
