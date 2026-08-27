package com.cmbchina.backend.auth.domain.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RoleResource {
    private String roleId;
    private String resourceKey;
    private LocalDateTime createdAt;
    private String createdBy;
}

