package com.cmbchina.backend.common.response;

import com.cmbchina.backend.common.exception.IErrorCode;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ResponseEntity<T> {

    private String returnCode;

    private String errorMsg;

    private T body;

    private ResponseEntity() {
    }

    public static <T> ResponseEntity<T> success() {
        ResponseEntity<T> responseEntity = new ResponseEntity<>();
        responseEntity.setReturnCode("SUC0000");
        return responseEntity;
    }

    public static <T> ResponseEntity<T> success(T body) {
        ResponseEntity<T> responseEntity = new ResponseEntity<>();
        responseEntity.setReturnCode("SUC0000");
        responseEntity.setBody(body);
        return responseEntity;
    }

    public static <T> ResponseEntity<T> failed(IErrorCode errorCode, Object... params) {
        ResponseEntity<T> responseEntity = new ResponseEntity<>();
        responseEntity.setReturnCode(errorCode.getErrorCodeStr());
        responseEntity.setErrorMsg(errorCode.getErrorMessage(params));
        return responseEntity;
    }

    public static <T> ResponseEntity<T> failed(String errorCode, String errorMsg) {
        ResponseEntity<T> responseEntity = new ResponseEntity<>();
        responseEntity.setReturnCode(errorCode);
        responseEntity.setErrorMsg(errorMsg);
        return responseEntity;
    }

    @Override
    public String toString() {
        return "ResponseEntity{" +
                "returnCode='" + returnCode + "'" +
                ", errorMsg='" + errorMsg + "'" +
                ", body=" + body +
                "}";
    }

}
