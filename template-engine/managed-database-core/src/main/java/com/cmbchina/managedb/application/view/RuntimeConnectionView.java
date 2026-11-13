package com.cmbchina.managedb.application.view;

import com.cmbchina.managedb.domain.RuntimeDatabaseConnection;
import lombok.Getter;

/**
 * 仅供经过服务鉴权的启动接口序列化，不进入业务状态或日志。
 */
@Getter
public final class RuntimeConnectionView {
    private final String datasourceKey;
    private final long datasourceId;
    private final String host;
    private final int port;
    private final String database, username, password, sslMode;

    /**
     * 显式映射允许返回的字段，不引入管理账号或连接池对象。
     */
    public RuntimeConnectionView(String datasourceKey, long datasourceId, RuntimeDatabaseConnection connection) {
        this.datasourceKey = datasourceKey;
        this.datasourceId = datasourceId;
        this.host = connection.getHost();
        this.port = connection.getPort();
        this.database = connection.getDatabase();
        this.username = connection.getUsername();
        this.password = connection.getPassword();
        this.sslMode = connection.getSslMode();
    }
}
