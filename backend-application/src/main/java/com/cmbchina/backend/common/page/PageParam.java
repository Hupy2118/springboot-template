package com.cmbchina.backend.common.page;

import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 分页查询参数，提供当前页码和页面大小，并对非法或超限参数使用默认值。
 */
@Setter
@NoArgsConstructor
public class PageParam {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private Integer current;

    private Integer pageSize;

    public int getCurrent() {
        if (current == null || current < 1) {
            return 1;
        }
        return current;
    }

    public int getPageSize() {
        if (pageSize == null || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            return DEFAULT_PAGE_SIZE;
        }
        return pageSize;
    }

}
