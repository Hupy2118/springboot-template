package com.cmbchina.backend.common.exception;

import com.cmb.bee.commons.exceptions.BizException;
import com.cmb.bee.commons.exceptions.SysException;
import com.cmb.bee.web.entity.BeeResponseEntity;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.validation.ConstraintViolationException;
import java.util.stream.Collectors;

import static com.cmbchina.backend.common.exception.error.code.BusinessErrorCode.PARAMETER_INVALID;
import static com.cmbchina.backend.common.exception.error.code.SystemErrorCode.UNKNOWN_EXCEPTION;


/**
 * @author 陈家位80315592
 * @date 2026/7/3
 */
@ControllerAdvice
@Slf4j
public class WebGlobalExceptionHandler {
    private static final String ERROR_MESSAGE_SEPARATOR = "；";

    @ResponseBody
    @ExceptionHandler(ConstraintViolationException.class)
    public BeeResponseEntity<String> handleConstraintViolationException(ConstraintViolationException e) {
        log.error("请求参数异常：{}", e.getMessage(), e);
        return BeeResponseEntity.failed(PARAMETER_INVALID, "请求参数非法，请检查后重试");
    }

    @ResponseBody
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public BeeResponseEntity<String> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        String exceptionMessage = StringUtils.join(
                e.getBindingResult().getAllErrors().stream().map(DefaultMessageSourceResolvable::getDefaultMessage).collect(Collectors.toList()),
                ERROR_MESSAGE_SEPARATOR
        );

        log.error("请求参数异常：{}", exceptionMessage, e);
        return BeeResponseEntity.failed(PARAMETER_INVALID, exceptionMessage);
    }

    @ResponseBody
    @ExceptionHandler(BizException.class)
    public BeeResponseEntity<String> handleBizException(BizException e) {
        log.error("业务异常：{}", e.getMessage(), e);
        return BeeResponseEntity.failed(e.getErrorCode(), e.getParams());
    }

    @ResponseBody
    @ExceptionHandler(SysException.class)
    public BeeResponseEntity<String> handleSysException(SysException e) {
        log.error("系统异常：{}", e.getMessage(), e);
        return BeeResponseEntity.failed(e.getErrorCode(), e.getParams());
    }

    @ResponseBody
    @ExceptionHandler(Throwable.class)
    public BeeResponseEntity<String> handleThrowable(Throwable e) {
        log.error("未知异常：{}", e.getMessage(), e);
        return BeeResponseEntity.failed(UNKNOWN_EXCEPTION);
    }
}
