package com.cmbchina.backend.common.exception;

import java.io.Serializable;
import java.text.MessageFormat;

public interface IErrorCode extends Serializable {

    String getErrorMessage();

    default String getErrorCodeStr() {
        return this.toString();
    }

    default String getErrorMessage(Object... params) {
        return MessageFormat.format(this.getErrorMessage(), params);
    }
}
