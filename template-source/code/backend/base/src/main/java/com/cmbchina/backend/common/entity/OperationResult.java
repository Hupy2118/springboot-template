package com.cmbchina.backend.common.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class OperationResult {

    private boolean success;

    public static OperationResult success() {
        return new OperationResult(true);
    }
}