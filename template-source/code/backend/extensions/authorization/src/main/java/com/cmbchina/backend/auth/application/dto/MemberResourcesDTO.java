package com.cmbchina.backend.auth.application.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MemberResourcesDTO {
    private String memberId;
    private List<String> resourceKeys;
}
