package com.cmbchina.backend.auth.infrastructure.repository.converter;

import com.cmbchina.backend.auth.domain.entity.RoleMember;
import com.cmbchina.backend.auth.infrastructure.po.RoleMemberPO;
import org.mapstruct.Mapper;

import java.util.List;

@Mapper(componentModel = "spring")
public interface RoleMemberConverter {
    RoleMember toEntity(RoleMemberPO source);

    List<RoleMember> toEntities(List<RoleMemberPO> source);

    List<RoleMemberPO> toPOs(List<RoleMember> source);
}

