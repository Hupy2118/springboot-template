package com.cmbchina.backend.auth.application.assembler;

import com.cmbchina.backend.auth.application.dto.RoleDTO;
import com.cmbchina.backend.auth.application.dto.RoleUpsertDTO;
import com.cmbchina.backend.auth.domain.entity.Role;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class RoleAssembler {
    public Role toCreatedRole(int roleNumber, RoleUpsertDTO request, String actor) {
        Role role = new Role();
        role.setRoleId(String.format(Locale.ROOT, "ROLE%03d", roleNumber));
        role.setRoleName(request.getName().trim());
        role.setDescription(trimToNull(request.getDescription()));
        role.setEnable(true);
        role.setIsSystem(false);
        role.setCreatedBy(actor);
        return role;
    }

    public RoleDTO toDTO(Role role) {
        return new RoleDTO(role.getRoleId(), role.getRoleName(), role.getDescription(),
                Boolean.TRUE.equals(role.getEnable()), Boolean.TRUE.equals(role.getIsSystem()));
    }

    private String trimToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
