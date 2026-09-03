package com.cmbchina.backend.auth.common.interceptor;

import com.cmbchina.backend.auth.application.dto.MemberResourcesDTO;
import com.cmbchina.backend.auth.application.service.ResourceApplicationService;
import com.cmbchina.backend.auth.common.annotation.RequireAnyResource;
import com.cmbchina.backend.auth.common.context.BaseUserData;
import com.cmbchina.backend.auth.common.context.BaseUserDataThreadHodler;
import com.cmbchina.backend.auth.domain.exception.AuthErrorCode;
import com.cmbchina.backend.common.exception.BizException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ResourcePermissionInterceptorTest {

    @AfterEach
    void cleanUp() {
        BaseUserDataThreadHodler.clear();
    }

    @Test
    void allowsWhenCurrentUserOwnsAnyRequiredResource() throws Exception {
        ResourceApplicationService resourceService = mock(ResourceApplicationService.class);
        when(resourceService.getMemberResources("member-001")).thenReturn(new MemberResourcesDTO(
                "member-001", Arrays.asList("resource-other", "resource-b")));
        BaseUserDataThreadHodler.set(new BaseUserData("member-001", "张伟"));

        boolean allowed = new ResourcePermissionInterceptor(resourceService).preHandle(
                new MockHttpServletRequest(), new MockHttpServletResponse(), handler("secured"));

        assertTrue(allowed);
    }

    @Test
    void rejectsWhenCurrentUserOwnsNoneOfRequiredResources() throws Exception {
        ResourceApplicationService resourceService = mock(ResourceApplicationService.class);
        when(resourceService.getMemberResources("member-002")).thenReturn(new MemberResourcesDTO(
                "member-002", Collections.singletonList("resource-other")));
        BaseUserDataThreadHodler.set(new BaseUserData("member-002", "李娜"));

        BizException exception = assertThrows(BizException.class,
                () -> new ResourcePermissionInterceptor(resourceService).preHandle(
                        new MockHttpServletRequest(), new MockHttpServletResponse(), handler("secured")));

        assertEquals(AuthErrorCode.FORBIDDEN, exception.getErrorCode());
    }

    @Test
    void rejectsAnnotatedEndpointWhenUserIsNotLoggedIn() throws Exception {
        ResourceApplicationService resourceService = mock(ResourceApplicationService.class);

        BizException exception = assertThrows(BizException.class,
                () -> new ResourcePermissionInterceptor(resourceService).preHandle(
                        new MockHttpServletRequest(), new MockHttpServletResponse(), handler("secured")));

        assertEquals(AuthErrorCode.UNAUTHENTICATED, exception.getErrorCode());
        verifyNoInteractions(resourceService);
    }

    @Test
    void ignoresEndpointWithoutPermissionAnnotation() throws Exception {
        ResourceApplicationService resourceService = mock(ResourceApplicationService.class);

        assertTrue(new ResourcePermissionInterceptor(resourceService).preHandle(
                new MockHttpServletRequest(), new MockHttpServletResponse(), handler("open")));
        verifyNoInteractions(resourceService);
    }

    private HandlerMethod handler(String methodName) throws NoSuchMethodException {
        TestController controller = new TestController();
        Method method = TestController.class.getDeclaredMethod(methodName);
        return new HandlerMethod(controller, method);
    }

    private static class TestController {

        @RequireAnyResource({"resource-a", "resource-b"})
        public void secured() {
        }

        public void open() {
        }
    }
}
