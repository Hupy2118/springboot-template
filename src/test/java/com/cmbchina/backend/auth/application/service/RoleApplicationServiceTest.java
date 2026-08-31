package com.cmbchina.backend.auth.application.service;

import com.cmbchina.backend.auth.domain.entity.Role;
import com.cmbchina.backend.auth.domain.entity.RoleMember;
import com.cmbchina.backend.auth.common.context.BaseUserData;
import com.cmbchina.backend.auth.common.context.BaseUserDataThreadHodler;
import com.cmbchina.backend.auth.application.dto.MemberDTO;
import com.cmbchina.backend.auth.application.dto.RoleMembersDTO;
import com.cmbchina.backend.common.exception.BizException;
import com.cmbchina.backend.auth.application.dto.RoleStatusDTO;
import com.cmbchina.backend.auth.application.dto.RoleUpsertDTO;
import com.cmbchina.backend.auth.application.assembler.RoleAssembler;
import com.cmbchina.backend.auth.domain.repository.ResourceRepository;
import com.cmbchina.backend.auth.domain.repository.RoleMemberRepository;
import com.cmbchina.backend.auth.domain.repository.RoleRepository;
import com.cmbchina.backend.auth.domain.repository.RoleResourceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import java.util.Arrays;
import java.util.Collections;

@ExtendWith(MockitoExtension.class)
class RoleApplicationServiceTest {

    @Mock
    private RoleRepository roleRepository;
    @Mock
    private ResourceRepository resourceRepository;
    @Mock
    private RoleResourceRepository roleResourceRepository;
    @Mock
    private RoleMemberRepository roleMemberRepository;
    @Mock
    private RoleAssembler roleAssembler;
    private RoleApplicationService service;

    @BeforeEach
    void setUp() {
        service = new RoleApplicationService(roleAssembler, roleRepository, resourceRepository,
                roleMemberRepository, roleResourceRepository);
        BaseUserDataThreadHodler.set(new BaseUserData("admin", "管理员"));
    }

    @AfterEach
    void cleanUp() {
        BaseUserDataThreadHodler.clear();
    }

    @Test
    void systemRoleCannotBeDeleted() {
        Role systemRole = role("ROLE001", true, true);
        when(roleRepository.findByRoleId("ROLE001")).thenReturn(systemRole);

        BizException exception = assertThrows(BizException.class,
                () -> service.deleteRole("ROLE001"));

        assertEquals("XCD1B04", exception.getErrorCode().getErrorCodeStr());
        verify(roleResourceRepository, never()).deleteByRoleId("ROLE001");
    }

    @Test
    void createUsesCurrentMemberIdFromHolderAsActor() {
        RoleUpsertDTO request = new RoleUpsertDTO();
        request.setName("测试角色");
        Role createdRole = role("ROLE001", true, false);
        when(roleRepository.findMaxGeneratedRoleNumber()).thenReturn(null);
        when(roleAssembler.toCreatedRole(1, request, "admin")).thenReturn(createdRole);

        service.createRole(request);

        verify(roleAssembler).toCreatedRole(1, request, "admin");
        verify(roleRepository).save(createdRole);
    }

    @Test
    void disablingLastAdministratorIsRejected() {
        Role role = role("ROLE001", true, false);
        when(roleRepository.findByRoleId("ROLE001")).thenReturn(role);
        when(roleResourceRepository.countActiveManagers("system_authorization_management")).thenReturn(0L);

        BizException exception = assertThrows(BizException.class,
                () -> service.setRoleStatus("ROLE001", status(false)));

        assertEquals("XCD1B05", exception.getErrorCode().getErrorCodeStr());
        verify(roleRepository).update(role);
    }

    @Test
    void bindMembersReplacesRoleMembers() {
        Role role = role("ROLE001", true, false);
        when(roleRepository.findByRoleId("ROLE001")).thenReturn(role);
        when(roleResourceRepository.countActiveManagers("system_authorization_management")).thenReturn(1L);
        when(roleMemberRepository.findByRoleId("ROLE001"))
                .thenReturn(Arrays.asList(
                        new RoleMember("ROLE001", "member-001", "张伟", null, "old"),
                        new RoleMember("ROLE001", "member-002", "李娜", null, "old")));

        RoleMembersDTO result = service.bindMembers("ROLE001",
                Arrays.asList(new MemberDTO("member-001", "张伟"), new MemberDTO("member-003", "王芳")));

        assertEquals("ROLE001", result.getRoleId());
        assertEquals(2, result.getMembers().size());
        verify(roleMemberRepository).deleteBatch("ROLE001", Collections.singletonList("member-002"));
        verify(roleMemberRepository).saveAll(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void writeWithoutCurrentUserIsRejectedInServiceLayer() {
        BaseUserDataThreadHodler.clear();

        BizException exception = assertThrows(BizException.class,
                () -> service.deleteRole("ROLE001"));

        assertEquals("XCD1B11", exception.getErrorCode().getErrorCodeStr());
    }

    @Test
    void getRoleResourcesRejectsMissingRoleBeforeQueryingRelations() {
        BizException exception = assertThrows(BizException.class,
                () -> service.getRoleResources("ROLE404"));

        assertEquals("XCD1B02", exception.getErrorCode().getErrorCodeStr());
        verifyNoInteractions(roleResourceRepository);
    }

    @Test
    void getRoleMembersRejectsMissingRoleBeforeQueryingRelations() {
        BizException exception = assertThrows(BizException.class,
                () -> service.getRoleMembers("ROLE404"));

        assertEquals("XCD1B02", exception.getErrorCode().getErrorCodeStr());
        verifyNoInteractions(roleMemberRepository);
    }

    private RoleStatusDTO status(boolean active) {
        RoleStatusDTO request = new RoleStatusDTO();
        request.setEnable(active);
        return request;
    }

    private Role role(String roleId, boolean active, boolean system) {
        Role role = new Role();
        role.setRoleId(roleId);
        role.setEnable(active);
        role.setIsSystem(system);
        return role;
    }
}
