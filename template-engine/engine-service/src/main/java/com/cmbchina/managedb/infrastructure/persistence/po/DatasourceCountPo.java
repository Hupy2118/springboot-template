package com.cmbchina.managedb.infrastructure.persistence.po;

import lombok.Getter;
import lombok.Setter;

/**
 * 分组计数的数据库投影，不向应用层暴露。
 */
@Getter
@Setter
public class DatasourceCountPo {
    private Long datasourceId;
    private Long total;
}
