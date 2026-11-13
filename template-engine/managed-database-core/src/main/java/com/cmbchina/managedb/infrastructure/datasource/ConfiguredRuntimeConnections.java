package com.cmbchina.managedb.infrastructure.datasource;

import com.cmb.bee.commons.exceptions.BizException;
import com.cmbchina.managedb.domain.ResourceErrorCode;
import com.cmbchina.managedb.domain.RuntimeConnectionPort;
import com.cmbchina.managedb.domain.RuntimeDatabaseConnection;

import java.util.HashMap;
import java.util.Map;

/**
 * 地址和 Schema 来自同一管理挂载，账号来自单独的调试配置。
 */
public final class ConfiguredRuntimeConnections implements RuntimeConnectionPort {
    private final Map<Long, DatabaseMountProperties.Mount> mounts = new HashMap<>();
    private final Map<Long, RuntimeConnectionProperties.Entry> entries = new HashMap<>();

    /**
     * 只检查重复配置，不要求所有挂载均启用本地调试。
     */
    public ConfiguredRuntimeConnections(DatabaseMountProperties properties, RuntimeConnectionProperties runtime) {
        for (DatabaseMountProperties.Mount mount : properties.getList()) {
            mounts.put(mount.getDatasourceId(), mount);
        }
        if (runtime.getList() == null) {
            throw new IllegalArgumentException("调试连接配置无效");
        }
        for (RuntimeConnectionProperties.Entry entry : runtime.getList()) {
            if (entry == null || entry.getDatasourceId() == null || entry.getDatasourceId() <= 0
                    || entries.put(entry.getDatasourceId(), entry) != null || !mounts.containsKey(entry.getDatasourceId())) {
                throw new IllegalArgumentException("调试连接挂载标识无效或重复");
            }
        }
    }

    /**
     * 不接受客户端位置参数，不回退到管理账号或默认账号。
     */
    @Override
    public RuntimeDatabaseConnection resolve(long datasourceId) {
        DatabaseMountProperties.Mount mount = mounts.get(datasourceId);
        RuntimeConnectionProperties.Entry entry = entries.get(datasourceId);
        if (mount == null || entry == null || blank(entry.getUsername()) || blank(entry.getPassword())
                || entry.getUsername().equalsIgnoreCase(mount.getUsername()) || "root".equalsIgnoreCase(entry.getUsername())
                || !allowedSslMode(entry.getSslMode())) {
            throw new BizException(ResourceErrorCode.RUNTIME_CONNECTION_NOT_CONFIGURED);
        }
        return new RuntimeDatabaseConnection(mount.getHost(), mount.getPort(), mount.getDbName(), entry.getUsername(), entry.getPassword(),
                entry.getSslMode());
    }

    /**
     * 仅允许 Connector/J 支持的固定 TLS 策略。
     */
    private static boolean allowedSslMode(String value) {
        return "DISABLED".equals(value) || "PREFERRED".equals(value) || "REQUIRED".equals(value) || "VERIFY_CA".equals(value)
                || "VERIFY_IDENTITY".equals(value);
    }

    /**
     * 判断缺配置时不输出原始字段内容。
     */
    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
