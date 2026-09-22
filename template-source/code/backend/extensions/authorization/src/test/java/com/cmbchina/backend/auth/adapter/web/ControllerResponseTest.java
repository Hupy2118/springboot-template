package com.cmbchina.backend.auth.adapter.web;

import com.cmbchina.backend.common.response.ResponseEntity;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ControllerResponseTest {

    @Test
    void everyAuthorizationEndpointUsesCommonResponseEntity() {
        assertController(RoleController.class);
        assertController(ResourceController.class);
        assertController(MemberController.class);
    }

    private void assertController(Class<?> controllerType) {
        for (Method method : controllerType.getDeclaredMethods()) {
            if (isEndpoint(method)) {
                assertEquals(ResponseEntity.class, method.getReturnType(),
                        controllerType.getSimpleName() + "." + method.getName());
            }
        }
    }

    private boolean isEndpoint(Method method) {
        return method.isAnnotationPresent(GetMapping.class)
                || method.isAnnotationPresent(PostMapping.class)
                || method.isAnnotationPresent(PutMapping.class)
                || method.isAnnotationPresent(DeleteMapping.class);
    }
}
