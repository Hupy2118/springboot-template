package com.cmbchina.managedb.infrastructure.datasource;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 只在基础设施绑定挂载配置，不生成包含凭据的 toString。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "devagent-studio")
public class DatabaseMountProperties {
    private List<Mount> list = new ArrayList<>();

    /**
     * 单个永久固定的实例与 Schema 位置，凭据仅用于内部连接池。
     */
    @Getter
    @Setter
    public static class Mount {
        private Long datasourceId;
        private String host;
        private String dbName;
        private Integer port;
        private String username;
        private String password;
    }
}
