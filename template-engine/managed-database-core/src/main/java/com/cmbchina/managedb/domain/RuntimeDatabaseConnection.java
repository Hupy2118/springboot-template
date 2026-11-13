package com.cmbchina.managedb.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 仅在内存传递的运行时连接，不生成包含凭据的 toString。
 */
@Getter
@AllArgsConstructor
public final class RuntimeDatabaseConnection {
    private final String host;
    private final int port;
    private final String database, username, password, sslMode;
}
