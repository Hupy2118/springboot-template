package com.cmbchina.managedb.application.view;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 确认前的精确删除范围，不包含数据库连接或可执行 SQL。
 */
@Getter
public final class ResourceCleanupPreview {
    private final String datasourceKey, scope, previewHash;
    private final List<String> tableNames;
    private final String warning = "删除不可恢复；只清理已登记归属的表。整份数据源资源回收成功后，该数据源资源不能继续建表或获取调试连接。";

    /**
     * 固定本次清单和指纹，不允许调用者修改删除范围。
     */
    public ResourceCleanupPreview(String datasourceKey, String scope, List<String> names, String hash) {
        this.datasourceKey = datasourceKey;
        this.scope = scope;
        this.tableNames = Collections.unmodifiableList(new ArrayList<>(names));
        this.previewHash = hash;
    }
}
