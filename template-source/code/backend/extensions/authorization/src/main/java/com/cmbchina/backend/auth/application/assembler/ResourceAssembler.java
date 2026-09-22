package com.cmbchina.backend.auth.application.assembler;

import com.cmbchina.backend.auth.application.dto.ResourceDTO;
import com.cmbchina.backend.auth.domain.entity.Resource;
import org.springframework.stereotype.Component;

@Component
public class ResourceAssembler {
    public ResourceDTO toDTO(Resource resource) {
        return new ResourceDTO(resource.getKey(), resource.getName(), resource.getDescription(),
                resource.getOrigin(), resource.getType(), resource.getTargetResourceRef());
    }
}
