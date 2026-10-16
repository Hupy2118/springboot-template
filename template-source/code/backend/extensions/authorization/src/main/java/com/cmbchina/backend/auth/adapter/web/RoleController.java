package com.cmbchina.backend.auth.adapter.web;

import com.cmb.bee.web.entity.BeeResponseEntity;
import com.cmbchina.backend.auth.application.dto.MemberDTO;
import com.cmbchina.backend.auth.application.dto.RoleDTO;
import com.cmbchina.backend.auth.application.dto.RoleMembersDTO;
import com.cmbchina.backend.auth.application.dto.RoleResourcesDTO;
import com.cmbchina.backend.auth.application.dto.RoleStatusDTO;
import com.cmbchina.backend.auth.application.dto.RoleUpsertDTO;
import com.cmbchina.backend.auth.application.service.RoleApplicationService;
import com.cmbchina.backend.auth.common.annotation.RequireAnyResource;
import com.cmbchina.backend.auth.domain.constant.AuthConstants;
import com.cmbchina.backend.common.entity.page.PageParam;
import com.cmbchina.backend.common.entity.page.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/authorization/roles")
@RequireAnyResource(AuthConstants.SYSTEM_MANAGEMENT_RESOURCE)
public class RoleController {

    private final RoleApplicationService roleService;

    @GetMapping
    public BeeResponseEntity<PageResult<RoleDTO>> list(@ModelAttribute PageParam query) {
        return BeeResponseEntity.success(roleService.listRoles(query));
    }

    @GetMapping("/{roleId}")
    public BeeResponseEntity<RoleDTO> get(@PathVariable String roleId) {
        return BeeResponseEntity.success(roleService.getRole(roleId));
    }

    @PostMapping
    public BeeResponseEntity<RoleDTO> create(@Valid @RequestBody RoleUpsertDTO request) {
        return BeeResponseEntity.success(roleService.createRole(request));
    }

    @PutMapping("/{roleId}")
    public BeeResponseEntity<RoleDTO> update(@PathVariable String roleId, @Valid @RequestBody RoleUpsertDTO request) {
        return BeeResponseEntity.success(roleService.updateRole(
                roleId, request));
    }

    @PutMapping("/{roleId}/status")
    public BeeResponseEntity<RoleDTO> setStatus(@PathVariable String roleId, @Valid @RequestBody RoleStatusDTO request) {
        return BeeResponseEntity.success(roleService.setRoleStatus(
                roleId, request));
    }

    @DeleteMapping("/{roleId}")
    public BeeResponseEntity<Void> delete(@PathVariable String roleId) {
        roleService.deleteRole(roleId);
        return BeeResponseEntity.success();
    }

    @GetMapping("/{roleId}/resources")
    public BeeResponseEntity<RoleResourcesDTO> getResources(@PathVariable String roleId) {
        return BeeResponseEntity.success(roleService.getRoleResources(roleId));
    }

    @PutMapping("/{roleId}/resources")
    public BeeResponseEntity<RoleResourcesDTO> bindResources(@PathVariable String roleId,
                                                             @RequestBody List<String> resourceKeys) {
        return BeeResponseEntity.success(roleService.bindResources(
                roleId, resourceKeys));
    }

    @GetMapping("/{roleId}/members")
    public BeeResponseEntity<RoleMembersDTO> getMembers(@PathVariable String roleId) {
        return BeeResponseEntity.success(roleService.getRoleMembers(roleId));
    }

    @PutMapping("/{roleId}/members")
    public BeeResponseEntity<RoleMembersDTO> bindMembers(@PathVariable String roleId,
                                                         @RequestBody List<MemberDTO> members) {
        return BeeResponseEntity.success(roleService.bindMembers(roleId, members));
    }
}
