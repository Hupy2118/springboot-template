package com.cmbchina.backend.standalone.login;

import com.cmb.oa.one.account.oauth.client.login.context.TokenContextHolder;
import com.cmb.oa.one.account.oauth.client.login.model.LocalToken;
import org.springframework.web.filter.OncePerRequestFilter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;

public final class MockIdentityFilter extends OncePerRequestFilter {
    static final String COOKIE = "standalone_identity";
    private final MockIdentityService identity;
    public MockIdentityFilter(MockIdentityService identity) { this.identity = identity; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        TokenContextHolder.clear();
        try {
            if (!InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress()) {
                response.sendError(403); return;
            }
            String token = null;
            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) token = header.substring(7);
            else if (request.getCookies() != null) for (Cookie cookie : request.getCookies())
                if (COOKIE.equals(cookie.getName())) token = cookie.getValue();
            String user = identity.verify(token);
            if (user != null) TokenContextHolder.set(new LocalToken(user));
            chain.doFilter(request, response);
        } finally { TokenContextHolder.clear(); }
    }
}
