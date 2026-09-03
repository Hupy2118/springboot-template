package com.cmbchina.backend.auth.application.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
public class RoleUpsertDTO {
    @NotBlank
    @Size(max = 32)
    private String name;

    @Size(max = 128)
    private String description;
}



