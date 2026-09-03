package com.cmbchina.backend.auth.adapter.web;

import com.cmbchina.backend.auth.application.dto.RoleUpsertDTO;
import com.cmbchina.backend.auth.application.service.ResourceApplicationService;
import com.cmbchina.backend.auth.application.service.RoleApplicationService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CurrentUserControllerTest {

    @Test
    void roleWriteDelegatesWithoutActorParameter() {
        RoleApplicationService roleService = mock(RoleApplicationService.class);
        RoleController controller = new RoleController(roleService);
        RoleUpsertDTO request = new RoleUpsertDTO();
        request.setName("测试角色");
        controller.create(request);

        verify(roleService).createRole(request);
    }

    @Test
    void myResourcesDelegatesCurrentUserResolutionToService() {
        ResourceApplicationService resourceService = mock(ResourceApplicationService.class);
        ResourceController controller = new ResourceController(resourceService);
        controller.getMyResources();

        verify(resourceService).getCurrentMemberResources();
    }
}
