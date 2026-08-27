package com.cmbchina.backend.auth.application.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResourceDTO {
    private String resourceKey;
    private String name;
    private String description;
    private String origin;
    private String type;
    private String targetResourceRef;
}



