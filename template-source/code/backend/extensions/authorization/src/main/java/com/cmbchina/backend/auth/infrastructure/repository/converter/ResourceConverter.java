package com.cmbchina.backend.auth.infrastructure.repository.converter;

import com.cmbchina.backend.auth.domain.entity.Resource;
import com.cmbchina.backend.auth.infrastructure.po.ResourcePO;
import org.mapstruct.Mapper;

import java.util.List;

@Mapper(componentModel = "spring")
public interface ResourceConverter {
    Resource toEntity(ResourcePO source);

    ResourcePO toPO(Resource source);

    List<Resource> toEntities(List<ResourcePO> source);
}

