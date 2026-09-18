package com.cmbchina.backend.gateway.config;

import java.util.ArrayList;
import java.util.List;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 保存 Gateway 认证和内部身份的非敏感配置引用。 */
@Validated
@ConfigurationProperties(prefix = "gateway.security")
public class GatewaySecurityProperties {
    @NotBlank
    private String mode = "enterprise";
    @NotBlank
    private String applicationId = "generated-application";
    @NotBlank
    private String issuer = "xcode-generated-gateway";
    @NotBlank
    private String audience = "application-internal-services";
    @NotBlank
    private String internalIdentitySecret;
    @NotBlank
    private String serviceToken;
    @Min(1)
    @Max(120)
    private long internalIdentityTtlSeconds = 60;
    @Min(60)
    private long mockTokenTtlSeconds = 21600;
    private List<String> publicPaths = new ArrayList<>();

    /** 返回公开认证模式。 */
    public String getMode() { return mode; }

    /** 设置公开认证模式。 */
    public void setMode(String mode) { this.mode = mode; }

    /** 返回应用标识。 */
    public String getApplicationId() { return applicationId; }

    /** 设置应用标识。 */
    public void setApplicationId(String applicationId) { this.applicationId = applicationId; }

    /** 返回内部身份签发者。 */
    public String getIssuer() { return issuer; }

    /** 设置内部身份签发者。 */
    public void setIssuer(String issuer) { this.issuer = issuer; }

    /** 返回内部身份受众。 */
    public String getAudience() { return audience; }

    /** 设置内部身份受众。 */
    public void setAudience(String audience) { this.audience = audience; }

    /** 返回本地内部身份签名密钥。 */
    public String getInternalIdentitySecret() { return internalIdentitySecret; }

    /** 设置本地内部身份签名密钥。 */
    public void setInternalIdentitySecret(String internalIdentitySecret) { this.internalIdentitySecret = internalIdentitySecret; }

    /** 返回 Gateway 服务令牌。 */
    public String getServiceToken() { return serviceToken; }

    /** 设置 Gateway 服务令牌。 */
    public void setServiceToken(String serviceToken) { this.serviceToken = serviceToken; }

    /** 返回普通请求内部身份有效期。 */
    public long getInternalIdentityTtlSeconds() { return internalIdentityTtlSeconds; }

    /** 设置普通请求内部身份有效期。 */
    public void setInternalIdentityTtlSeconds(long internalIdentityTtlSeconds) { this.internalIdentityTtlSeconds = internalIdentityTtlSeconds; }

    /** 返回 Mock 登录令牌有效期。 */
    public long getMockTokenTtlSeconds() { return mockTokenTtlSeconds; }

    /** 设置 Mock 登录令牌有效期。 */
    public void setMockTokenTtlSeconds(long mockTokenTtlSeconds) { this.mockTokenTtlSeconds = mockTokenTtlSeconds; }

    /** 返回无需公开认证的精确路径或前缀配置。 */
    public List<String> getPublicPaths() { return publicPaths; }

    /** 设置无需公开认证的精确路径或前缀配置。 */
    public void setPublicPaths(List<String> publicPaths) {
        this.publicPaths = publicPaths == null ? new ArrayList<>() : publicPaths;
    }

    /** 判断当前路径是否命中显式公开路径，末尾双星号表示前缀匹配。 */
    public boolean isPublicPath(String path) {
        if (path == null) {
            return false;
        }
        for (String configured : publicPaths) {
            if (configured == null) {
                continue;
            }
            if (configured.endsWith("/**") && path.startsWith(configured.substring(0, configured.length() - 3))) {
                return true;
            }
            if (configured.equals(path)) {
                return true;
            }
        }
        return false;
    }
}
