package com.cmbchina.backend.common.exception.error.code;

import com.cmb.bee.commons.errorcodes.ISysErrorCode;

/**
 * 通用系统异常错误码
 */
public enum SystemErrorCode implements ISysErrorCode {
    UNKNOWN_EXCEPTION("XCA9001", "应用发生异常，请稍后重试");

    private final String code;
    private final String errorMessage;

    SystemErrorCode(String code, String errorMessage) {
        this.code = code;
        this.errorMessage = errorMessage;
    }

    @Override
    public String getErrorCodeStr() {
        return code;
    }

    @Override
    public String getErrorMessage() {
        return errorMessage;
    }

    @Override
    public String getErrorMessage(Object... params) {
        if (params == null || params.length == 0) {
            return getErrorMessage();
        }

        return ISysErrorCode.super.getErrorMessage(params);
    }
}
