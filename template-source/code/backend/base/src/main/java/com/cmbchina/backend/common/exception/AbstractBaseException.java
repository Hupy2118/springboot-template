package com.cmbchina.backend.common.exception;

import lombok.Getter;

public class AbstractBaseException extends RuntimeException {

    private static final long serialVersionUID = 8742197704733072618L;

    @Getter
    private final IErrorCode errorCode;

    @Getter
    private transient Object[] params;

    public AbstractBaseException(IErrorCode errorCode) {
        super(errorCode.getErrorMessage());
        this.errorCode = errorCode;
    }

    public AbstractBaseException(IErrorCode errorCode, Object... params) {
        super(errorCode.getErrorMessage(params));
        this.errorCode = errorCode;
        this.params = params;
    }

    public AbstractBaseException(IErrorCode errorCode, Throwable cause) {
        super(getErrorMessage(errorCode), cause);
        this.errorCode = errorCode;
    }

    public AbstractBaseException(IErrorCode errorCode, Throwable cause, Object... params) {
        super(getErrorMessage(errorCode, params), cause);
        this.errorCode = errorCode;
        this.params = params;
    }

    private static String getErrorMessage(IErrorCode errorCode, Object... params) {
        return "[errorCode]:" +
                errorCode.getErrorCodeStr() +
                ",[errorMsg]:" +
                errorCode.getErrorMessage(params);
    }
}
