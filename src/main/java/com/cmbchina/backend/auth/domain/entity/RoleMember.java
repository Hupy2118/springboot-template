package com.cmbchina.backend.auth.domain.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RoleMember {
    private String roleId;
    private String memberId;
    private String memberName;
    private LocalDateTime createdAt;
    private String createdBy;
}

