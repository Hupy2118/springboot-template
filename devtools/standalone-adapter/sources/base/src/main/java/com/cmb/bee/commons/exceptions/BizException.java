package com.cmb.bee.commons.exceptions;

import com.cmb.bee.commons.errorcodes.IBizErrorCode;

/** Local compatibility surface only. */
public class BizException extends RuntimeException {
    private final IBizErrorCode errorCode;
    private final Object[] params;
    public BizException(IBizErrorCode errorCode, Object... params) {
        super(errorCode.getErrorMessage(params));
        this.errorCode = errorCode;
        this.params = params == null ? new Object[0] : params.clone();
    }
    public IBizErrorCode getErrorCode() { return errorCode; }
    public Object[] getParams() { return params.clone(); }
}
