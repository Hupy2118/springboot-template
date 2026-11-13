package com.cmbchina.managedb.application;

import com.cmbchina.managedb.application.view.DatasourceResourceView;
import com.cmbchina.managedb.domain.Actor;

/**
 * 普通 HTTP 入口调用的注册用例契约，创建请求不接收数据源编码。
 */
public interface DatasourceRegistration {
    /**
     * 同步创建数据源资源及固定挂载，持久化提交后返回只读结果；actor 记录触发注册的最终用户。
     */
    DatasourceResourceView register(String appKey, String appName, Actor actor);
}
