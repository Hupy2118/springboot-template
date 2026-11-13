package com.cmbchina.managedb.adapter.web.request;

import lombok.Getter;
import lombok.Setter;

/**
 * 单表创建或重建请求，不接收挂载位置或实体身份。
 */
@Getter
@Setter
public class OperationRequest {
    private String mode;
    private String sql;
    private Boolean allowDataLoss;
}
