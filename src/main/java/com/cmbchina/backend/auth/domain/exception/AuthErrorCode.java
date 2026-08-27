package com.cmbchina.backend.auth.domain.exception;

import com.cmbchina.backend.common.exception.IBizErrorCode;

/**
 * 权限模块稳定业务错误码。
 */
public enum AuthErrorCode implements IBizErrorCode {
    RESOURCE_NOT_FOUND("XCD1B01", "权限资源不存在"),
    WRITABLE_ROLE_NOT_FOUND("XCD1B02", "角色不存在或已删除"),
    SYSTEM_ROLE_CAN_NOT_DISABLE("XCD1B03", "系统角色不可停用"),
    SYSTEM_ROLE_CAN_NOT_DELETE("XCD1B04", "系统角色不可删除"),
    LAST_ADMINISTRATOR_REQUIRED("XCD1B05", "系统必须保留至少一个有效管理员"),
    ROLE_ID_EXHAUSTED("XCD1B06", "角色编号已经达到 ROLE999"),
    AUTHORIZATION_WRITE_BUSY("XCD1B07", "权限配置正在被修改，请稍后重试"),
    RESOURCE_CATALOG_INVALID("XCD1B08", "固定系统资源定义不正确"),
    INVALID_OPERATION_RESOURCE("XCD1B09", "业务接口只能绑定 operation 资源"),
    FORBIDDEN("XCD1B10", "无权限执行该操作"),
    UNAUTHENTICATED("XCD1B11", "当前请求未认证"),
    AUTHORIZATION_NOT_READY("XCD1B12", "权限运行时尚未就绪：{0}"),
    VALIDATION_FAILED("XCD1B13", "请求参数校验失败"),
    INVALID_ACTOR("XCD1B14", "操作人标识必须为 1 至 32 个字符"),
    MOCK_MEMBER_NOT_FOUND("XCD1B15", "模拟成员不存在");

    private final String code;
    private final String message;

    AuthErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public String getErrorCodeStr() {
        return code;
    }

    @Override
    public String getErrorMessage() {
        return message;
    }
}
