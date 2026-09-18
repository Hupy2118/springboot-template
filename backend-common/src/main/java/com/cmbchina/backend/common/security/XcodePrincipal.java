package com.cmbchina.backend.common.security;

import java.security.Principal;

/** 表示由公开认证适配器恢复出的最小可信用户身份。 */
public class XcodePrincipal implements Principal {
    private String userId;
    private String employeeId;
    private String sapId;
    private String enterpriseId;

    /** 提供 Jackson 反序列化所需的空构造方法。 */
    public XcodePrincipal() {
    }

    /** 创建最小可信用户身份。 */
    public XcodePrincipal(String userId, String employeeId, String sapId, String enterpriseId) {
        this.userId = userId;
        this.employeeId = employeeId;
        this.sapId = sapId;
        this.enterpriseId = enterpriseId;
    }

    /** 返回稳定用户标识。 */
    @Override
    public String getName() {
        return userId;
    }

    /** 返回稳定用户标识。 */
    public String getUserId() { return userId; }

    /** 设置稳定用户标识。 */
    public void setUserId(String userId) { this.userId = userId; }

    /** 返回员工标识。 */
    public String getEmployeeId() { return employeeId; }

    /** 设置员工标识。 */
    public void setEmployeeId(String employeeId) { this.employeeId = employeeId; }

    /** 返回 SAP 标识。 */
    public String getSapId() { return sapId; }

    /** 设置 SAP 标识。 */
    public void setSapId(String sapId) { this.sapId = sapId; }

    /** 返回企业标识。 */
    public String getEnterpriseId() { return enterpriseId; }

    /** 设置企业标识。 */
    public void setEnterpriseId(String enterpriseId) { this.enterpriseId = enterpriseId; }
}
