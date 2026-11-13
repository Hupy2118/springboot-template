package com.cmbchina.managedb.domain.valueobject;

import com.cmb.bee.commons.exceptions.BizException;
import com.cmbchina.managedb.domain.ResourceErrorCode;
import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * SaaS 生成的十二位十六进制数据源标识，不承担权限凭证职责。
 * 新编码以 d 前缀加十一位十六进制生成，格式与存量编码一致，下游校验无需调整。
 */
@Getter
@EqualsAndHashCode(of = "value")
public final class DatasourceKey {
    private final String value;

    /**
     * 校验编码格式，不自动补前缀或改变大小写。
     */
    public DatasourceKey(String value) {
        if (value == null || !value.matches("[0-9a-f]{12}")) {
            throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
        }
        this.value = value;
    }
}
