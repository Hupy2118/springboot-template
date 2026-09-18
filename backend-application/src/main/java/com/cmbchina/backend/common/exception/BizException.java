package com.cmbchina.backend.common.exception;

/**
 * 业务异常类型，用于在业务处理过程中携带可识别的业务错误码及相关参数。
 */
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
