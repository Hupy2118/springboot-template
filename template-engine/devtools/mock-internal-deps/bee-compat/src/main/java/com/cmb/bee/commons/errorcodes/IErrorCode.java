package com.cmb.bee.commons.errorcodes;

public interface IErrorCode {
    String getErrorMessage();

    default String getErrorMessage(Object... params) {
        return getErrorMessage();
    }

    default String getErrorCodeStr() {
        return this instanceof Enum ? ((Enum<?>) this).name() : getClass().getSimpleName();
    }
}
