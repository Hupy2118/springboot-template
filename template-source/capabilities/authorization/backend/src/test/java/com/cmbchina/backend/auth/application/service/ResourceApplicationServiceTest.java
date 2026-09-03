package com.cmbchina.backend.auth.application.service;

import com.cmbchina.backend.auth.application.assembler.ResourceAssembler;
import com.cmbchina.backend.auth.common.context.BaseUserData;
import com.cmbchina.backend.auth.common.context.BaseUserDataThreadHodler;
import com.cmbchina.backend.auth.domain.repository.ResourceRepository;
import com.cmbchina.backend.auth.domain.repository.RoleResourceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResourceApplicationServiceTest {

    @Mock
    private RoleResourceRepository roleResourceRepository;
    @Mock
    private ResourceRepository resourceRepository;
    private ResourceApplicationService service;

    @BeforeEach
    void setUp() {
        service = new ResourceApplicationService(resourceRepository, roleResourceRepository, new ResourceAssembler());
    }

    @AfterEach
    void cleanUp() {
        BaseUserDataThreadHodler.clear();
    }

    @Test
    void returnsEffectiveAllowUnionFromMapper() {
        when(roleResourceRepository.findEffectiveResourceKeys("member-1"))
                .thenReturn(Arrays.asList("approve_order", "page_orders"));
        assertEquals(2, service.getMemberResources("member-1").getResourceKeys().size());
    }

    @Test
    void returnsCurrentMemberResourcesFromThreadHolder() {
        BaseUserDataThreadHodler.set(new BaseUserData("member-2", "李娜"));
        when(roleResourceRepository.findEffectiveResourceKeys("member-2"))
                .thenReturn(Arrays.asList("approve_order", "page_orders"));

        assertEquals("member-2", service.getCurrentMemberResources().getMemberId());
    }

}
