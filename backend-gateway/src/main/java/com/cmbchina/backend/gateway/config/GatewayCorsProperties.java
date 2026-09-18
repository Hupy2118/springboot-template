package com.cmbchina.backend.gateway.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 保存公开 Gateway 的精确跨域来源配置。 */
@ConfigurationProperties(prefix = "gateway.cors")
public class GatewayCorsProperties {
    private List<String> allowedOrigins = new ArrayList<>();

    /** 返回允许访问 Gateway 的浏览器 Origin。 */
    public List<String> getAllowedOrigins() { return allowedOrigins; }
    /** 设置允许访问 Gateway 的浏览器 Origin。 */
    public void setAllowedOrigins(List<String> allowedOrigins) { this.allowedOrigins = allowedOrigins; }
}
