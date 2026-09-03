package com.cmbchina.backend.auth.application.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;

@Data
public class RoleStatusDTO {
    @NotNull
    private Boolean enable;
}



