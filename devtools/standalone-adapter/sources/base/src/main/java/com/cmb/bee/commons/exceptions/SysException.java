package com.cmb.bee.commons.exceptions;

import com.cmb.bee.commons.errorcodes.ISysErrorCode;

/** Local compatibility surface only. */
public class SysException extends RuntimeException {
    private final ISysErrorCode errorCode;
    private final Object[] params;
    public SysException(ISysErrorCode errorCode, Object... params) {
        super(errorCode.getErrorMessage(params));
        this.errorCode = errorCode;
        this.params = params == null ? new Object[0] : params.clone();
    }
    public ISysErrorCode getErrorCode() { return errorCode; }
    public Object[] getParams() { return params.clone(); }
}
