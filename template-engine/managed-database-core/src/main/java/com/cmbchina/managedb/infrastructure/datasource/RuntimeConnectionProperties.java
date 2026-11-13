package com.cmbchina.managedb.infrastructure.datasource;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 调试凭据独立配置，位置仍由现有固定挂载提供。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "managed-database.runtime")
public class RuntimeConnectionProperties {
    private List<Entry> list = new ArrayList<>();

    /**
     * 每个挂载显式配置调试账号；禁止包含凭据的自动 toString。
     */
    @Getter
    @Setter
    public static class Entry {
        private Long datasourceId;
        private String username;
        private String password;
        private String sslMode = "VERIFY_IDENTITY";
    }
}
