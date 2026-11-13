package com.cmbchina.managedb.common.page;

import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 沿用模板的分页入参和单页大小限制。
 */
@Setter
@NoArgsConstructor
public class PageParam {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private Integer current;
    private Integer pageSize;

    /**
     * 返回有效页码，未配置或非法页码按第一页处理。
     */
    public int getCurrent() {
        return current == null || current < 1 ? 1 : current;
    }

    /**
     * 返回受限制的单页大小，非法值使用默认大小。
     */
    public int getPageSize() {
        return pageSize == null || pageSize < 1 || pageSize > MAX_PAGE_SIZE
                ? DEFAULT_PAGE_SIZE : pageSize;
    }
}
