package com.cmbchina.backend.auth.application.service;

import com.cmbchina.backend.auth.application.assembler.RoleAssembler;
import com.cmbchina.backend.auth.application.dto.*;
import com.cmbchina.backend.auth.common.context.BaseUserData;
import com.cmbchina.backend.auth.common.context.BaseUserDataThreadHodler;
import com.cmbchina.backend.auth.domain.constant.AuthConstants;
import com.cmbchina.backend.auth.domain.entity.Role;
import com.cmbchina.backend.auth.domain.entity.RoleMember;
import com.cmbchina.backend.auth.domain.entity.RoleResource;
import com.cmbchina.backend.auth.domain.exception.AuthErrorCode;
import com.cmbchina.backend.auth.domain.repository.ResourceRepository;
import com.cmbchina.backend.auth.domain.repository.RoleMemberRepository;
import com.cmbchina.backend.auth.domain.repository.RoleRepository;
import com.cmbchina.backend.auth.domain.repository.RoleResourceRepository;
import com.cmbchina.backend.common.exception.BizException;
import com.cmbchina.backend.common.lock.NamedLockRepository;
import com.cmbchina.backend.common.page.PageParam;
import com.cmbchina.backend.common.page.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 负责角色、资源关系和成员关系的应用用例。
 */
@Service
@RequiredArgsConstructor
public class RoleApplicationService {

    private final RoleAssembler roleAssembler;
    private final RoleRepository roleRepository;
    private final ResourceRepository resourceRepository;
    private final RoleMemberRepository roleMemberRepository;
    private final RoleResourceRepository roleResourceRepository;
    private final NamedLockRepository namedLockRepository;

    public PageResult<RoleDTO> listRoles(PageParam query) {
        int safeCurrent = query.getCurrent();
        int safePageSize = query.getPageSize();
        return PageResult.convert(roleRepository.page(safeCurrent, safePageSize), roleAssembler::toDTO);
    }

    public RoleDTO getRole(String roleId) {
        Role role = getRoleById(roleId);
        return roleAssembler.toDTO(role);
    }

    @Transactional(rollbackFor = Exception.class)
    public RoleDTO createRole(RoleUpsertDTO request) {
        String actor = currentActor();
        namedLockRepository.acquire(AuthConstants.ROLE_ID_LOCK);
        Integer current = roleRepository.findMaxGeneratedRoleNumber();
        int next = current == null ? 1 : current + 1;
        if (next > 999) {
            throw new BizException(AuthErrorCode.ROLE_ID_EXHAUSTED);
        }
        Role role = roleAssembler.toCreatedRole(next, request, actor);
        roleRepository.save(role);
        return roleAssembler.toDTO(role);
    }

    @Transactional(rollbackFor = Exception.class)
    public RoleDTO updateRole(String roleId, RoleUpsertDTO request) {
        String actor = currentActor();
        Role role = getRoleById(roleId);
        role.setRoleName(request.getName().trim());
        role.setDescription(request.getDescription());
        role.setUpdatedBy(actor);
        roleRepository.update(role);
        return roleAssembler.toDTO(role);
    }

    @Transactional(rollbackFor = Exception.class)
    public RoleDTO setRoleStatus(String roleId, RoleStatusDTO request) {
        String actor = currentActor();
        Role role = getRoleById(roleId);
        if (Boolean.TRUE.equals(role.getIsSystem())) {
            throw new BizException(AuthErrorCode.SYSTEM_ROLE_CAN_NOT_DISABLE);
        }
        boolean enable = Boolean.TRUE.equals(request.getEnable());
        if (Boolean.valueOf(enable).equals(role.getEnable())) {
            return roleAssembler.toDTO(role);
        }
        role.setEnable(enable);
        role.setUpdatedBy(actor);
        roleRepository.update(role);
        if (!enable) {
            ensureAdministratorRemains();
        }
        return roleAssembler.toDTO(role);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteRole(String roleId) {
        String actor = currentActor();
        Role role = getRoleById(roleId);
        if (Boolean.TRUE.equals(role.getIsSystem())) {
            throw new BizException(AuthErrorCode.SYSTEM_ROLE_CAN_NOT_DELETE);
        }
        roleResourceRepository.deleteByRoleId(roleId);
        roleMemberRepository.deleteByRoleId(roleId);
        roleRepository.softDelete(role, actor);
        ensureAdministratorRemains();
    }

    public RoleResourcesDTO getRoleResources(String roleId) {
        getRoleById(roleId);
        return new RoleResourcesDTO(roleId, roleResourceRepository.findResourceKeysByRoleId(roleId));
    }

    @Transactional(rollbackFor = Exception.class)
    public RoleResourcesDTO bindResources(String roleId, List<String> resourceKeys) {
        String actor = currentActor();
        Role role = getRoleById(roleId);

        Set<String> desiredResourceKeys = new LinkedHashSet<>(resourceRepository.findExistingKeys(resourceKeys));
        if (Boolean.TRUE.equals(role.getIsSystem())) {
            desiredResourceKeys.add(AuthConstants.SYSTEM_MANAGEMENT_RESOURCE);
        }
        Set<String> bindingResourceKeys = new HashSet<>(roleResourceRepository.findResourceKeysByRoleId(roleId));

        List<String> resourceKeysToDelete = bindingResourceKeys.stream()
                .filter(key -> !desiredResourceKeys.contains(key))
                .collect(Collectors.toList());
        if (!resourceKeysToDelete.isEmpty()) {
            roleResourceRepository.deleteBatch(roleId, resourceKeysToDelete);
        }

        Set<String> resourceKeysToAdd = desiredResourceKeys.stream()
                .filter(key -> !bindingResourceKeys.contains(key))
                .collect(Collectors.toSet());
        if (!resourceKeysToAdd.isEmpty()) {
            LocalDateTime createdAt = LocalDateTime.now();
            List<RoleResource> items = resourceKeysToAdd.stream()
                    .map(key -> new RoleResource(roleId, key, createdAt, actor))
                    .collect(Collectors.toList());
            roleResourceRepository.saveAll(items);
        }
        ensureAdministratorRemains();

        return new RoleResourcesDTO(roleId, new ArrayList<>(desiredResourceKeys));
    }

    public RoleMembersDTO getRoleMembers(String roleId) {
        getRoleById(roleId);
        List<MemberDTO> members = roleMemberRepository.findByRoleId(roleId).stream()
                .map(m -> new MemberDTO(m.getMemberId(), m.getMemberName()))
                .collect(Collectors.toList());
        return new RoleMembersDTO(roleId, members);
    }

    @Transactional(rollbackFor = Exception.class)
    public RoleMembersDTO bindMembers(String roleId, List<MemberDTO> members) {
        String actor = currentActor();
        Role role = getRoleById(roleId);
        if (Boolean.TRUE.equals(role.getIsSystem()) && members.isEmpty()) {
            throw new BizException(AuthErrorCode.LAST_ADMINISTRATOR_REQUIRED);
        }

        Map<String, String> desiredMembers = members.stream()
                .collect(Collectors.toMap(MemberDTO::getMemberId, MemberDTO::getMemberName));
        Set<String> bindingByMemberId = roleMemberRepository.findByRoleId(roleId).stream()
                .map(RoleMember::getMemberId)
                .collect(Collectors.toSet());

        List<String> memberIdsToDelete = bindingByMemberId.stream()
                .filter(memberId -> !desiredMembers.containsKey(memberId))
                .collect(Collectors.toList());
        if (!memberIdsToDelete.isEmpty()) {
            roleMemberRepository.deleteBatch(roleId, memberIdsToDelete);
        }

        LocalDateTime createdAt = LocalDateTime.now();
        List<RoleMember> membersToAdd = desiredMembers.entrySet().stream()
                .filter(entry -> !bindingByMemberId.contains(entry.getKey()))
                .map(entry -> new RoleMember(roleId, entry.getKey(), entry.getValue(), createdAt, actor))
                .collect(Collectors.toList());
        if (!membersToAdd.isEmpty()) {
            roleMemberRepository.saveAll(membersToAdd);
        }
        ensureAdministratorRemains();

        return new RoleMembersDTO(roleId, members);
    }

    private void ensureAdministratorRemains() {
        if (roleResourceRepository.countActiveManagers(AuthConstants.SYSTEM_MANAGEMENT_RESOURCE) < 1) {
            throw new BizException(AuthErrorCode.LAST_ADMINISTRATOR_REQUIRED);
        }
    }

    private Role getRoleById(String roleId) {
        Role role = roleRepository.findByRoleId(roleId);
        if (role == null) {
            throw new BizException(AuthErrorCode.WRITABLE_ROLE_NOT_FOUND);
        }
        return role;
    }

    private String currentActor() {
        BaseUserData userData = BaseUserDataThreadHodler.get();
        if (userData == null) {
            throw new BizException(AuthErrorCode.UNAUTHENTICATED);
        }
        String actor = userData.getMemberId();
        if (actor == null || actor.trim().isEmpty() || actor.length() > 32) {
            throw new BizException(AuthErrorCode.INVALID_ACTOR);
        }
        return actor;
    }
}
