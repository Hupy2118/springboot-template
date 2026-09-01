package com.cmbchina.backend.common.exception;

import java.io.Serializable;
import java.text.MessageFormat;

/**
 * 错误码统一抽象，定义错误码标识和错误信息，并支持使用参数格式化错误信息。
 */
public interface IErrorCode extends Serializable {

    String getErrorMessage();

    default String getErrorCodeStr() {
        return this.toString();
    }

    default String getErrorMessage(Object... params) {
        return MessageFormat.format(this.getErrorMessage(), params);
    }
}
