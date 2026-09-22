package com.cmbchina.backend.common.exception;

import com.cmbchina.backend.common.response.ResponseEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;

import javax.validation.ConstraintViolationException;

@Slf4j
@RestControllerAdvice
public class BaseExceptionHandler {

    @ExceptionHandler(BizException.class)
    @ResponseStatus(value = HttpStatus.OK)
    public ResponseEntity<Object> exceptionHandle(BizException e) {
        log.error("发生了业务异常，异常原因:", e);
        return ResponseEntity.failed(e.getErrorCode(), e.getParams());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class,
            ConstraintViolationException.class, HttpMessageNotReadableException.class})
    @ResponseStatus(value = HttpStatus.OK)
    public ResponseEntity<Object> validationExceptionHandle(Exception e) {
        log.warn("请求参数校验失败", e);
        return ResponseEntity.failed("validation_failed", "请求参数校验失败");
    }
}
