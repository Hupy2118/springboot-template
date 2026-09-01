package com.cmbchina.backend.common.page;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 分页查询结果，统一封装总条数、分页信息和当前页数据，并支持结果类型转换。
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {
    /**
     * 总数
     */
    private long total;
    /**
     * 数据列表
     */
    private List<T> list;
    /**
     * 页码
     */
    private long current;
    /**
     * 页面大小
     */
    private long pageSize;
    /**
     * 总页数
     */
    private long totalPage;

    public static <T, F> PageResult<T> of(long total, int current, int pageSize,
                                          List<F> origin, Function<F, T> converter) {
        long totalPage = (pageSize <= 0) ? 0 : (total + pageSize - 1) / pageSize;
        List<F> safeList = Optional.ofNullable(origin).orElse(new ArrayList<>());
        List<T> targetList = safeList.stream().map(converter).collect(Collectors.toList());
        return new PageResult<>(total, targetList, current, pageSize, totalPage);
    }

    public static <T, F> PageResult<T> convert(PageResult<F> origin, Function<F, T> converter) {
        List<F> safeList = Optional.ofNullable(origin.getList()).orElse(new ArrayList<>());
        List<T> targetList = safeList.stream().map(converter).collect(Collectors.toList());
        return new PageResult<>(
                origin.getTotal(), targetList, origin.getCurrent(), origin.getPageSize(), origin.getTotalPage());
    }

}
