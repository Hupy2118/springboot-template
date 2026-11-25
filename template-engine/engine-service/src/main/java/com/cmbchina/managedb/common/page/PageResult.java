package com.cmbchina.managedb.common.page;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 沿用模板的分页结果结构，提供列表项转换。
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {

    private long total;
    private List<T> list;
    private long current;
    private long pageSize;
    private long totalPage;

    /**
     * 根据分页信息和转换器创建分页结果。
     */
    public static <T, F> PageResult<T> of(long total, int current, int pageSize,
                                          List<F> origin, Function<F, T> converter) {
        long pages = pageSize <= 0 ? 0 : (total + pageSize - 1) / pageSize;
        List<F> source = origin == null ? Collections.emptyList() : origin;
        List<T> items = source.stream().map(converter).collect(Collectors.toList());
        return new PageResult<>(total, items, current, pageSize, pages);
    }

    /**
     * 保持分页信息不变，只转换列表项类型。
     */
    public static <T, F> PageResult<T> convert(PageResult<F> origin, Function<F, T> converter) {
        List<F> source = origin.getList() == null ? Collections.emptyList() : origin.getList();
        List<T> items = source.stream().map(converter).collect(Collectors.toList());
        return new PageResult<>(origin.getTotal(), items, origin.getCurrent(),
                origin.getPageSize(), origin.getTotalPage());
    }
}
