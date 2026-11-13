package com.cmbchina.managedb.common.exception;

import com.cmb.bee.commons.exceptions.BizException;
import com.cmb.bee.web.entity.BeeResponseEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import javax.validation.ConstraintViolationException;

/**
 * 普通 JSON 接口的统一异常适配，不依赖业务域枚举或返回原始异常正文。
 * 通过 basePackages 限定只处理 com.cmbchina.managedb 包下的 Controller，
 * 避免与 engine-service 的 ApiExceptionHandler 交叉处理。
 */
@Slf4j
@RestControllerAdvice(basePackages = "com.cmbchina.managedb")
public class BaseExceptionHandler {
    /**
     * 依错误契约映射真实 HTTP 状态，不用 HTTP 200 包装业务失败。
     */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<BeeResponseEntity<String>> exceptionHandle(BizException e) {
        String code = e.getErrorCode().getErrorCodeStr();
        HttpStatus status;
        switch (code) {
            case "SQL_UNSUPPORTED":
            case "PRIMARY_KEY_REQUIRED":
            case "INVALID_ARGUMENT":
            case "INVALID_RESOURCE_ARGUMENT":
                status = HttpStatus.BAD_REQUEST;
                break;
            case "SEED_REQUEST_TOO_LARGE":
                status = HttpStatus.PAYLOAD_TOO_LARGE;
                break;
            case "IDEMPOTENCY_CONFLICT":
            case "SEED_DATA_CONFLICT":
            case "TABLE_BINDING_CONFLICT":
            case "STRUCTURE_MISMATCH":
            case "RESOURCE_BUSY":
                status = HttpStatus.CONFLICT;
                break;
            case "RESOURCE_NOT_FOUND":
                status = HttpStatus.NOT_FOUND;
                break;
            case "RESOURCE_SERVICE_NOT_CONFIGURED":
            case "EXECUTION_CAPACITY_EXHAUSTED":
            case "RUNTIME_CONNECTION_NOT_CONFIGURED":
            case "DATABASE_UNAVAILABLE":
                status = HttpStatus.SERVICE_UNAVAILABLE;
                break;
            default:
                status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        log.warn("业务异常，错误码={}", code);
        BeeResponseEntity<String> response = BeeResponseEntity.failed(e.getErrorCode(), e.getParams());
        return ResponseEntity.status(status).body(response);
    }

    /**
     * 参数异常不回显请求正文，防止连接配置等内容被公开。
     */
    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<BeeResponseEntity<String>> validationExceptionHandle(Exception e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(BeeResponseEntity.failed("INVALID_ARGUMENT", "请求参数无效"));
    }

    /**
     * 保持方法不支持的标准状态，避免被统一未知异常误判成服务器故障。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<BeeResponseEntity<String>> methodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(BeeResponseEntity.failed("INVALID_ARGUMENT", "请求方法不支持"));
    }

    /**
     * 保持媒体类型不支持的标准状态。
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<BeeResponseEntity<String>> unsupportedMediaType(HttpMediaTypeNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(BeeResponseEntity.failed("INVALID_ARGUMENT", "请求媒体类型不支持"));
    }

    /**
     * 未分类错误不推断注册未提交，不记录可能含凭据的堆栈。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<BeeResponseEntity<String>> internalError(Exception e) {
        log.error("内部异常，类型={}", e.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(BeeResponseEntity.failed("INTERNAL_ERROR", "操作结果未确认，请勿自动重试"));
    }
}
