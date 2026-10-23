package com.cmb.bee.commons.errorcodes;

/** Local compatibility surface only; not an enterprise SDK. */
public interface IBizErrorCode {
    String getErrorCodeStr();
    String getErrorMessage();
    default String getErrorMessage(Object... params) {
        return java.text.MessageFormat.format(getErrorMessage(), params == null ? new Object[0] : params);
    }
}
