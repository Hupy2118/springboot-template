package com.cmbchina.managedb.application;

import com.cmbchina.managedb.application.query.GetDatasourceResourceQuery;
import com.cmbchina.managedb.application.view.DatasourceResourceView;

/**
 * 入站查询契约，当前没有对外查询 Controller。
 */
public interface DatasourceResourceQueries {
    /**
     * 返回应用原绑定及真实状态，固定服务密钥由入口负责。
     */
    DatasourceResourceView get(GetDatasourceResourceQuery query);
}
