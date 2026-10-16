package com.cmbchina.backend.common.exception.error.code;

import com.cmb.bee.commons.errorcodes.IBizErrorCode;

/**
 * 通用业务异常错误码
 */
public enum BusinessErrorCode implements IBizErrorCode {
    PARAMETER_INVALID("XCA0001", "{0}");

    private final String code;
    private final String errorMessage;

    BusinessErrorCode(String code, String errorMessage) {
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

        return IBizErrorCode.super.getErrorMessage(params);
    }
}
