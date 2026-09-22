package com.cmbchina.backend.auth.infrastructure.repository.converter;

import com.cmbchina.backend.auth.domain.entity.Role;
import com.cmbchina.backend.auth.infrastructure.po.RolePO;
import org.mapstruct.Mapper;

import java.util.List;

@Mapper(componentModel = "spring")
public interface RoleConverter {
    Role toEntity(RolePO source);

    RolePO toPO(Role source);

    List<Role> toEntities(List<RolePO> source);
}
