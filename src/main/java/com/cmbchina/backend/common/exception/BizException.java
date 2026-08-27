package com.cmbchina.backend.common.exception;

public class BizException extends AbstractBaseException {

    public BizException(IErrorCode errorCode) {
        super(errorCode);
    }

    public BizException(IErrorCode errorCode, Object... params) {
        super(errorCode, params);
    }

    public BizException(IErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }

    public BizException(IErrorCode errorCode, Throwable cause, Object... params) {
        super(errorCode, cause, params);
    }
}
