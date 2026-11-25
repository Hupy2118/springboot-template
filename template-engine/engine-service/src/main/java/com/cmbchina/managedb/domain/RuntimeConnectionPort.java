package com.cmbchina.managedb.domain;

/**
 * 只为可信启动调用提供配置，不复用管理连接或开放 SQL 执行。
 */
public interface RuntimeConnectionPort {
    /**
     * 按应用固定挂载解析专用调试连接，缺配置必须失败。
     */
    RuntimeDatabaseConnection resolve(long datasourceId);
}
