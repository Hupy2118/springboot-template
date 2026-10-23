package com.cmb.bee.web.entity;

import com.cmb.bee.commons.errorcodes.IBizErrorCode;
import com.cmb.bee.commons.errorcodes.ISysErrorCode;

/** Implements only the response methods consumed by the 1016 source. */
public final class BeeResponseEntity<T> {
    private final String returnCode;
    private final String errorMsg;
    private final T body;
    private BeeResponseEntity(String code, String message, T body) {
        this.returnCode = code; this.errorMsg = message; this.body = body;
    }
    public static <T> BeeResponseEntity<T> success() { return success(null); }
    public static <T> BeeResponseEntity<T> success(T body) { return new BeeResponseEntity<T>("SUC0000", "", body); }
    public static <T> BeeResponseEntity<T> failed(IBizErrorCode code, Object... params) {
        return new BeeResponseEntity<T>(code.getErrorCodeStr(), code.getErrorMessage(params), null);
    }
    public static <T> BeeResponseEntity<T> failed(ISysErrorCode code, Object... params) {
        return new BeeResponseEntity<T>(code.getErrorCodeStr(), code.getErrorMessage(params), null);
    }
    public String getReturnCode() { return returnCode; }
    public String getErrorMsg() { return errorMsg; }
    public T getBody() { return body; }
}
