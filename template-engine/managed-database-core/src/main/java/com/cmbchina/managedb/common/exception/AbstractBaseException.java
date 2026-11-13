package com.cmbchina.managedb.common.exception;

import com.cmb.bee.commons.errorcodes.IErrorCode;
import lombok.Getter;

/**
 * 保存业务错误码及非敏感的提示参数。
 */
public abstract class AbstractBaseException extends RuntimeException {

    private static final long serialVersionUID = 1L;
    @Getter
    private final IErrorCode errorCode;
    private final transient Object[] params;

    /**
     * 使用错误码创建基础异常。
     */
    protected AbstractBaseException(IErrorCode errorCode) {
        this(errorCode, new Object[0]);
    }

    /**
     * 使用错误码和非敏感参数创建基础异常。
     */
    protected AbstractBaseException(IErrorCode errorCode, Object... params) {
        this(errorCode, null, params);
    }

    /**
     * 使用错误码和内部原因创建基础异常。
     */
    protected AbstractBaseException(IErrorCode errorCode, Throwable cause) {
        this(errorCode, cause, new Object[0]);
    }

    /**
     * 保存内部原因，公开提示仍只使用业务错误码。
     */
    protected AbstractBaseException(IErrorCode errorCode, Throwable cause, Object... params) {
        super(errorCode.getErrorMessage(params), cause);
        this.errorCode = errorCode;
        this.params = params.clone();
    }

    /**
     * 返回提示参数副本，避免外部改变异常内容。
     */
    public Object[] getParams() {
        return params.clone();
    }
}
