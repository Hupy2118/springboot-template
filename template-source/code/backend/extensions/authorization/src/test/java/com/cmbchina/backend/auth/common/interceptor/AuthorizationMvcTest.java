package com.cmbchina.backend.auth.common.interceptor;

import com.cmb.bee.commons.exceptions.BizException;
import com.cmb.oa.one.account.oauth.client.login.context.TokenContextHolder;
import com.cmb.oa.one.account.oauth.client.login.model.LocalToken;
import com.cmbchina.backend.auth.adapter.web.ResourceController;
import com.cmbchina.backend.auth.adapter.web.RoleController;
import com.cmbchina.backend.auth.application.dto.MemberResourcesDTO;
import com.cmbchina.backend.auth.application.service.ResourceApplicationService;
import com.cmbchina.backend.auth.common.annotation.RequireAnyResource;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Uses the real Bee token types and authorization interceptor; requires the private Bee dependencies. */
class AuthorizationMvcTest {
    @Test void permissionRequiresLoginAndExplicitResourceWithoutSystemBypass() throws Exception {
        ResourceApplicationService service = mock(ResourceApplicationService.class);
        ResourcePermissionInterceptor interceptor = new ResourcePermissionInterceptor(service);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders");
        MockHttpServletResponse response = new MockHttpServletResponse();
        HandlerMethod protectedHandler = new HandlerMethod(new Endpoints(), Endpoints.class.getMethod("orders"));
        HandlerMethod publicHandler = new HandlerMethod(new Endpoints(), Endpoints.class.getMethod("publicEndpoint"));
        try (MockedStatic<TokenContextHolder> holder = mockStatic(TokenContextHolder.class)) {
            holder.when(TokenContextHolder::get).thenReturn(null);
            assertTrue(interceptor.preHandle(request, response, publicHandler));
            assertThrows(BizException.class, () -> interceptor.preHandle(request, response, protectedHandler));
            verifyNoInteractions(service);
            LocalToken token = mock(LocalToken.class); when(token.getSapId()).thenReturn("admin");
            holder.when(TokenContextHolder::get).thenReturn(token);
            when(service.getMemberResources("admin")).thenReturn(new MemberResourcesDTO("admin", Collections.singletonList("system_authorization_management")));
            assertThrows(BizException.class, () -> interceptor.preHandle(request, response, protectedHandler));
            when(service.getMemberResources("admin")).thenReturn(new MemberResourcesDTO("admin", Collections.singletonList("orders")));
            assertTrue(interceptor.preHandle(request, response, protectedHandler));
            when(service.getMemberResources("admin")).thenReturn(null);
            assertThrows(BizException.class, () -> interceptor.preHandle(request, response, protectedHandler));
        }
    }
    @Test void managementAnnotationsRemainOnReal1016Controllers() throws Exception {
        assertEquals("system_authorization_management", RoleController.class.getAnnotation(RequireAnyResource.class).value()[0]);
        assertEquals("system_authorization_management", ResourceController.class.getMethod("get", String.class).getAnnotation(RequireAnyResource.class).value()[0]);
        assertNull(ResourceController.class.getMethod("getMyResources").getAnnotation(RequireAnyResource.class));
    }
    @Test void actualMvcConfigurationMapsAuthorizationAndBusinessApiOnly() {
        ResourcePermissionInterceptor interceptor = new ResourcePermissionInterceptor(mock(ResourceApplicationService.class));
        Registrations registry = new Registrations();
        new com.cmbchina.backend.common.config.WebMvcConfiguration(interceptor).addInterceptors(registry);
        assertEquals(1, registry.values().size());
        org.springframework.web.servlet.handler.MappedInterceptor mapped =
                (org.springframework.web.servlet.handler.MappedInterceptor) registry.values().get(0);
        assertSame(interceptor, mapped.getInterceptor());
        org.springframework.util.AntPathMatcher matcher = new org.springframework.util.AntPathMatcher();
        assertTrue(mapped.matches("/api/authorization/roles", matcher));
        assertTrue(mapped.matches("/api/orders", matcher));
        assertFalse(mapped.matches("/health", matcher));
        assertFalse(mapped.matches("/assets/app.js", matcher));
    }
    static class Registrations extends org.springframework.web.servlet.config.annotation.InterceptorRegistry {
        java.util.List<Object> values() { return getInterceptors(); }
    }
    static class Endpoints {
        @RequireAnyResource("orders") public void orders() { }
        public void publicEndpoint() { }
    }
}
