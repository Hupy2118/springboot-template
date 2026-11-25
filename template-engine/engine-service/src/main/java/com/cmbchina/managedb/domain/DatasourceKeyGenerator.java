package com.cmbchina.managedb.domain;

import com.cmbchina.managedb.domain.valueobject.DatasourceKey;

/**
 * 数据源编码生成能力，不向领域层暴露随机源或加密库对象。
 */
public interface DatasourceKeyGenerator {
    /**
     * 每次使用新的随机内容生成十二位编码。
     */
    DatasourceKey generate();
}
