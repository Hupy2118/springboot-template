package com.cmbchina.backend.standalone.login;

import com.cmb.bee.web.entity.BeeResponseEntity;
import com.cmb.oa.one.account.oauth.client.login.context.TokenContextHolder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Map;

@RestController
@Profile({"dev", "local"})
@ConditionalOnProperty(name="standalone.mock.enabled", havingValue="true")
public class MockLoginController {
    private final MockIdentityService identity;
    public MockLoginController(MockIdentityService identity) { this.identity = identity; }
    @GetMapping("/api/sso/login") public ResponseEntity<?> loginSso(
            @RequestParam(value="code", required=false) String code, HttpServletRequest request) {
        try {
            String token = identity.loginSso(code);
            return signedIn(token, identity.ssoIdentity(), request);
        } catch (IllegalArgumentException e) { return ResponseEntity.badRequest().build(); }
    }
    @PostMapping("/api/standalone/login") public ResponseEntity<?> login(@RequestBody Map<String, String> input, HttpServletRequest request) {
        try {
            String token = identity.login(input.get("userId"), input.get("secret"));
            return signedIn(token, identity.identity(input.get("userId")), request);
        } catch (IllegalArgumentException e) { return ResponseEntity.status(401).build(); }
    }
    @GetMapping("/api/standalone/me") public ResponseEntity<?> current() {
        if (TokenContextHolder.get() == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(BeeResponseEntity.success(identity.identity(TokenContextHolder.get().getSapId())));
    }
    @PostMapping("/api/standalone/logout") public ResponseEntity<?> logout(HttpServletRequest request) {
        TokenContextHolder.clear();
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie("", request, 0)).body(BeeResponseEntity.success());
    }
    private ResponseEntity<?> signedIn(String token, Map<String, String> user, HttpServletRequest request) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.SET_COOKIE, cookie(token, request, 3600)).body(BeeResponseEntity.success(user));
    }
    private String cookie(String token, HttpServletRequest request, long seconds) {
        return ResponseCookie.from(MockIdentityFilter.COOKIE, token).httpOnly(true).secure(request.isSecure())
                .sameSite("Lax").path("/").maxAge(Duration.ofSeconds(seconds)).build().toString();
    }
}
