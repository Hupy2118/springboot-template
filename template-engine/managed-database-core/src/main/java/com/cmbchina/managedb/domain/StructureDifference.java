package com.cmbchina.managedb.domain;

import lombok.Getter;

/**
 * 可展示的结构差异，不包含连接或行数据。
 */
@Getter
public final class StructureDifference {
    private final String category, target, expected, actual;

    /**
     * 固定一条差异。
     */
    public StructureDifference(String category, String target, String expected, String actual) {
        this.category = category;
        this.target = target;
        this.expected = clip(expected);
        this.actual = clip(actual);
    }

    /**
     * 差异摘要限制文本长度，完整结构只留在内存。
     */
    private static String clip(String value) {
        return value == null || value.length() <= 256 ? value : value.substring(0, 256);
    }
}
