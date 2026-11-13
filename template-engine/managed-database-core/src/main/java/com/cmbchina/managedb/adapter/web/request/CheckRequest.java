package com.cmbchina.managedb.adapter.web.request;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 启动检查请求，由 Studio 提交当前完整建表 SQL 清单。
 */
@Getter
@Setter
public class CheckRequest {
    private List<String> sqls;
}
