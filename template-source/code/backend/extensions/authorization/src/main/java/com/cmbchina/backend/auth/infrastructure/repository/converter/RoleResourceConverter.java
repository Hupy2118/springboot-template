package com.cmbchina.backend.auth.infrastructure.repository.converter;

import com.cmbchina.backend.auth.domain.entity.RoleResource;
import com.cmbchina.backend.auth.infrastructure.po.RoleResourcePO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface RoleResourceConverter {
    @Mapping(target = "id", ignore = true)
    RoleResourcePO toPO(RoleResource source);

    List<RoleResourcePO> toPOs(List<RoleResource> source);
}

