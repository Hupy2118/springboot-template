package com.cmbchina.managedb.domain;

import com.cmb.bee.commons.errorcodes.IErrorCode;
import lombok.RequiredArgsConstructor;

/**
 * 资源业务错误契约，不依赖 HTTP 或数据库异常类型。
 */
@RequiredArgsConstructor
public enum ResourceErrorCode implements IErrorCode {
    OPERATION_ABANDONED("用户已申请放弃，停止后续步骤"),
    QUERY_FILTER_UNSUPPORTED("一期暂不支持条件筛选，请使用空条件查询"),
    TABLE_DATA_UNSUPPORTED("表数据预览要求主键且字段不超过 200 个"),
    TABLE_DATA_TOO_LARGE("本页数据量过大，请减小每页条数后重新查询"),
    TABLE_DATA_QUERY_FAILED("表数据读取失败，请检查资源状态后重新查询"),
    SQL_UNSUPPORTED("SQL 语法或结构比较暂不支持"),
    PRIMARY_KEY_REQUIRED("建表 SQL 必须显式声明 PRIMARY KEY"),
    TABLE_BINDING_CONFLICT("表位置冲突或归属不可用"),
    CLEANUP_PREVIEW_CHANGED("待清理资源已变化，请重新预览并确认"),
    TABLE_REFERENCED("目标表存在外键引用或循环依赖，不能安全删除"),
    STRUCTURE_MISMATCH("真实结构与当前设计不同"),
    RESOURCE_BUSY("应用操作状态冲突，请查询当前操作"),
    IDEMPOTENCY_CONFLICT("幂等键已绑定不同的请求内容"),
    SEED_DATA_CONFLICT("现有权限数据与 Seed 身份约束冲突"),
    SEED_REQUEST_TOO_LARGE("权限 Seed 请求超过字节限制"),
    EXECUTION_CAPACITY_EXHAUSTED("执行容量暂不可用，请稍后重试"),
    SQL_EXECUTION_FAILED("数据库明确拒绝本次操作"),
    EXECUTION_UNKNOWN("执行结果尚未确认，禁止自动重试"),
    INVALID_ARGUMENT("请求参数无效"),
    INVALID_RESOURCE_ARGUMENT("数据库资源参数无效"),
    RESOURCE_NOT_FOUND("应用或执行记录不存在"),
    RESOURCE_SERVICE_NOT_CONFIGURED("数据库资源服务尚未配置"),
    RUNTIME_CONNECTION_NOT_CONFIGURED("专用调试连接尚未配置"),
    DATABASE_UNAVAILABLE("当前没有可访问的数据库挂载"),
    DATASOURCE_KEY_GENERATION_FAILED("数据源标识已存在，重试 3 次后仍无法生成可用编码"),
    INTERNAL_ERROR("操作结果未确认，请勿自动重试");

    private final String message;

    /**
     * 返回脱敏业务提示。
     */
    @Override
    public String getErrorMessage() {
        return message;
    }
}
