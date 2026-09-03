package com.cmbchina.backend.auth.application.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RoleDTO {
    private String roleId;
    private String name;
    private String description;
    private boolean enable;
    private boolean system;
}



