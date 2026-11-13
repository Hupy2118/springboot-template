package com.cmb.bee.web.entity;

import com.cmb.bee.commons.errorcodes.IErrorCode;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.ALWAYS)
public final class BeeResponseEntity<T> {
    private final String returnCode;
    private final String errorMsg;
    private final T body;

    private BeeResponseEntity(String returnCode, String errorMsg, T body) {
        this.returnCode = returnCode;
        this.errorMsg = errorMsg;
        this.body = body;
    }

    public static <T> BeeResponseEntity<T> success(T body) {
        return new BeeResponseEntity<>("SUC0000", "", body);
    }

    public static <T> BeeResponseEntity<T> failed(IErrorCode code, Object... params) {
        return failed(code.getErrorCodeStr(), code.getErrorMessage(params));
    }

    public static <T> BeeResponseEntity<T> failed(String code, String message) {
        return new BeeResponseEntity<>(code, message, null);
    }

    public String getReturnCode() {
        return returnCode;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public T getBody() {
        return body;
    }
}
