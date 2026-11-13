package com.cmbchina.managedb.domain;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一页只读预览结果，单元格保留精确文本或 SQL NULL，不暴露 JDBC 类型。
 */
@Getter
public final class TableDataPage {
    private final List<Map<String, String>> rows;
    private final Map<String, String> columnFormats;
    private final boolean hasMore, truncated;

    /**
     * 深拷贝行和格式映射，保证已读取的页面不能被调用者修改。
     */
    public TableDataPage(List<Map<String, String>> rows, Map<String, String> formats, boolean hasMore, boolean truncated) {
        List<Map<String, String>> copy = new ArrayList<>();
        for (Map<String, String> row : rows) {
            copy.add(Collections.unmodifiableMap(new LinkedHashMap<>(row)));
        }
        this.rows = Collections.unmodifiableList(copy);
        this.columnFormats = Collections.unmodifiableMap(new LinkedHashMap<>(formats));
        this.hasMore = hasMore;
        this.truncated = truncated;
    }
}
