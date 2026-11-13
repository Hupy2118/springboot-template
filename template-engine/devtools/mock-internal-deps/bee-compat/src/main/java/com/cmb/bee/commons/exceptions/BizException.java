package com.cmb.bee.commons.exceptions;

import com.cmb.bee.commons.errorcodes.IErrorCode;

public class BizException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final IErrorCode errorCode;
    private final transient Object[] params;

    public BizException(IErrorCode errorCode, Object... params) {
        super(errorCode.getErrorMessage(params));
        this.errorCode = errorCode;
        this.params = params.clone();
    }

    public IErrorCode getErrorCode() {
        return errorCode;
    }

    public Object[] getParams() {
        return params.clone();
    }
}
