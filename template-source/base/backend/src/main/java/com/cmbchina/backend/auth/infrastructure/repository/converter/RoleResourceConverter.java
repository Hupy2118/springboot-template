package com.cmbchina.backend.auth.infrastructure.repository.converter;

import com.cmbchina.backend.auth.domain.entity.RoleResource;
import com.cmbchina.backend.auth.infrastructure.po.RoleResourcePO;
import org.mapstruct.Mapper;

import java.util.List;

@Mapper(componentModel = "spring")
public interface RoleResourceConverter {
    List<RoleResourcePO> toPOs(List<RoleResource> source);
}

