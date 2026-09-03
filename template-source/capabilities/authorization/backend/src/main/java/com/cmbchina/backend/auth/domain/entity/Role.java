package com.cmbchina.backend.auth.domain.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Role {
    private Integer id;
    private String roleId;
    private String roleName;
    private String description;
    private Boolean enable;
    private Boolean isSystem;
    private LocalDateTime createdAt;
    private String createdBy;
    private LocalDateTime updatedAt;
    private String updatedBy;
}
