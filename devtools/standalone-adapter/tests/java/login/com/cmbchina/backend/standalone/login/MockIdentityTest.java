package com.cmbchina.backend.standalone.login;

import com.cmb.oa.one.account.oauth.client.login.context.TokenContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import javax.servlet.ServletException;
import static org.junit.jupiter.api.Assertions.*;

class MockIdentityTest {
    private static final String SECRET="only-a-unit-test-credential-32-characters";
    private MockEnvironment env() { return new MockEnvironment().withProperty("server.address","127.0.0.1")
            .withProperty("standalone.mock.signing-key","only-a-unit-test-signing-key-32-characters")
            .withProperty("standalone.mock.login-secret",SECRET).withProperty("standalone.mock.users-json","{\"admin\":\"Admin\",\"member\":\"Member\"}"); }
    @Test void credentialsAndSignedIdentityAreRequired() {
        MockIdentityService service=new MockIdentityService(env(),new ObjectMapper());
        assertThrows(IllegalArgumentException.class,()->service.login("admin","wrong"));
        assertThrows(IllegalArgumentException.class,()->service.login("unknown",SECRET));
        String token=service.login("admin",SECRET);
        assertEquals("admin",service.verify(token));
        String[] parts=token.split("\\.");parts[1]=parts[1]+"x";
        assertNull(service.verify(String.join(".",parts)));
        assertNull(service.verify(null));
        assertThrows(IllegalArgumentException.class,()->new MockIdentityService(env().withProperty("server.address","0.0.0.0"),new ObjectMapper()));
        assertThrows(IllegalArgumentException.class,()->new MockIdentityService(env().withProperty("standalone.mock.signing-key","short"),new ObjectMapper()));
        assertThrows(IllegalArgumentException.class,()->new MockIdentityService(env().withProperty("standalone.mock.login-secret","short"),new ObjectMapper()));
    }
    @Test void defaultsNeedNoMockConfigurationAndTokensAreProcessLocal() {
        MockEnvironment defaults=new MockEnvironment().withProperty("server.address","127.0.0.1");
        MockIdentityService first=new MockIdentityService(defaults,new ObjectMapper());
        MockIdentityService restarted=new MockIdentityService(defaults,new ObjectMapper());
        assertEquals("member-001",first.ssoIdentity().get("userId"));
        assertEquals("Local Admin",first.ssoIdentity().get("userName"));
        String token=first.loginSso("mock-code");
        assertEquals("member-001",first.verify(token));
        assertNull(restarted.verify(token));
        assertThrows(IllegalArgumentException.class,()->first.login("member-001",""));
        assertThrows(IllegalArgumentException.class,()->first.login("member-001",SECRET));
        MockIdentityService empty=new MockIdentityService(defaults.withProperty("standalone.mock.signing-key","")
                .withProperty("standalone.mock.login-secret","").withProperty("standalone.mock.users-json",""),new ObjectMapper());
        assertEquals("member-001",empty.verify(empty.loginSso("mock-code")));
    }
    @Test void configuredKeysRemainStableAndInvalidUsersNeverFallBack() {
        MockIdentityService first=new MockIdentityService(env(),new ObjectMapper());
        MockIdentityService restarted=new MockIdentityService(env(),new ObjectMapper());
        assertEquals("admin",restarted.verify(first.login("admin",SECRET)));
        for(String users:new String[]{"{}","[]","invalid","{\"member-001\":\"\"}"})
            assertThrows(IllegalArgumentException.class,()->new MockIdentityService(
                    env().withProperty("standalone.mock.users-json",users),new ObjectMapper()));
    }
    @Test void requestContextIsClearedAfterSuccessFailureAndNextAnonymousRequest() throws Exception {
        MockIdentityService service=new MockIdentityService(env(),new ObjectMapper());
        MockIdentityFilter filter=new MockIdentityFilter(service);
        MockHttpServletRequest request=new MockHttpServletRequest();request.setRemoteAddr("127.0.0.1");
        request.addHeader("Authorization","Bearer "+service.login("admin",SECRET));
        filter.doFilter(request,new MockHttpServletResponse(),(req,res)->assertEquals("admin",TokenContextHolder.get().getSapId()));
        assertNull(TokenContextHolder.get());
        MockHttpServletRequest failure=new MockHttpServletRequest();failure.setRemoteAddr("127.0.0.1");
        failure.addHeader("Authorization","Bearer "+service.login("member",SECRET));
        assertThrows(ServletException.class,()->filter.doFilter(failure,new MockHttpServletResponse(),(req,res)->{throw new ServletException("test");}));
        assertNull(TokenContextHolder.get());
        MockHttpServletRequest anonymous=new MockHttpServletRequest();anonymous.setRemoteAddr("127.0.0.1");
        anonymous.addHeader("X-User-Id","admin");
        filter.doFilter(anonymous,new MockHttpServletResponse(),(req,res)->assertNull(TokenContextHolder.get()));
        MockHttpServletRequest remote=new MockHttpServletRequest();remote.setRemoteAddr("192.0.2.10");
        MockHttpServletResponse denied=new MockHttpServletResponse();
        filter.doFilter(remote,denied,(req,res)->fail("remote request must not reach application"));
        assertEquals(403,denied.getStatus());assertNull(TokenContextHolder.get());
    }
}
