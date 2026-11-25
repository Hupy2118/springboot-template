package com.cmbchina.managedb.domain;

import java.util.List;
import java.util.Optional;

/**
 * 纯 Java 数据库能力，不公开 AST、凭据、连接或配置对象。
 */
public interface DatabaseSchemaPort {
    /**
     * 返回可分配的挂载。
     */
    List<Long> availableDatasourceIds();

    /**
     * 校验挂载可访问性。
     */
    void verifyAccessible(long datasourceId);

    /**
     * 完整解析一条 CREATE 原文。
     */
    ParsedCreateTable parseCreate(String sql);

    /**
     * 按目标数据库标识规则形成唯一位置键。
     */
    String tableKey(long datasourceId, String name);

    /**
     * 精确读取实际结构，不存在与读取失败分别表达。
     */
    Optional<TableStructure> readTable(long datasourceId, String name);

    /**
     * 精确读取表及字段展示元数据；真实缺失与读取失败分别表达。
     */
    Optional<TableMetadata> readTableMetadata(long datasourceId, String name);

    /**
     * 判断其他真实表是否引用当前表，不返回其他数据源资源结构。
     */
    boolean hasIncomingReferences(long datasourceId, String name);

    /**
     * 执行一条已经校验并保存的 SQL，禁止自动重试。
     */
    SqlExecutionResult execute(long datasourceId, String sql);
}
