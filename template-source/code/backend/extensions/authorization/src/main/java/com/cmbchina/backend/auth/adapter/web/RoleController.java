package com.cmbchina.backend.auth.adapter.web;

import com.cmbchina.backend.auth.application.dto.*;
import com.cmbchina.backend.auth.application.service.RoleApplicationService;
import com.cmbchina.backend.auth.common.annotation.RequireAnyResource;
import com.cmbchina.backend.auth.domain.constant.AuthConstants;
import com.cmbchina.backend.common.page.PageParam;
import com.cmbchina.backend.common.page.PageResult;
import com.cmbchina.backend.common.response.ResponseEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/authorization/roles")
@RequireAnyResource(AuthConstants.SYSTEM_MANAGEMENT_RESOURCE)
public class RoleController {

    private final RoleApplicationService roleService;

    @GetMapping
    public ResponseEntity<PageResult<RoleDTO>> list(@ModelAttribute PageParam query) {
        return ResponseEntity.success(roleService.listRoles(query));
    }

    @GetMapping("/{roleId}")
    public ResponseEntity<RoleDTO> get(@PathVariable String roleId) {
        return ResponseEntity.success(roleService.getRole(roleId));
    }

    @PostMapping
    public ResponseEntity<RoleDTO> create(@Valid @RequestBody RoleUpsertDTO request) {
        return ResponseEntity.success(roleService.createRole(request));
    }

    @PutMapping("/{roleId}")
    public ResponseEntity<RoleDTO> update(@PathVariable String roleId, @Valid @RequestBody RoleUpsertDTO request) {
        return ResponseEntity.success(roleService.updateRole(
                roleId, request));
    }

    @PutMapping("/{roleId}/status")
    public ResponseEntity<RoleDTO> setStatus(@PathVariable String roleId, @Valid @RequestBody RoleStatusDTO request) {
        return ResponseEntity.success(roleService.setRoleStatus(
                roleId, request));
    }

    @DeleteMapping("/{roleId}")
    public ResponseEntity<Void> delete(@PathVariable String roleId) {
        roleService.deleteRole(roleId);
        return ResponseEntity.success();
    }

    @GetMapping("/{roleId}/resources")
    public ResponseEntity<RoleResourcesDTO> getResources(@PathVariable String roleId) {
        return ResponseEntity.success(roleService.getRoleResources(roleId));
    }

    @PutMapping("/{roleId}/resources")
    public ResponseEntity<RoleResourcesDTO> bindResources(@PathVariable String roleId,
                                                          @RequestBody List<String> resourceKeys) {
        return ResponseEntity.success(roleService.bindResources(
                roleId, resourceKeys));
    }

    @GetMapping("/{roleId}/members")
    public ResponseEntity<RoleMembersDTO> getMembers(@PathVariable String roleId) {
        return ResponseEntity.success(roleService.getRoleMembers(roleId));
    }

    @PutMapping("/{roleId}/members")
    public ResponseEntity<RoleMembersDTO> bindMembers(@PathVariable String roleId,
                                                      @RequestBody List<MemberDTO> members) {
        return ResponseEntity.success(roleService.bindMembers(roleId, members));
    }
}
