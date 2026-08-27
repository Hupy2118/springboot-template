package com.cmbchina.backend.auth.application.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/** 模拟登录请求。 */
@Data
public class MockLoginRequest {

    @NotBlank
    @Size(max = 32)
    private String memberId;
}
