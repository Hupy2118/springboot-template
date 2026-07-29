package com.cmbchina.backend.infrastructure.common.page;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

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

    public static <T, F> PageResult<T> convert(Page<F> originPage, Function<F, T> converter) {
        List<F> originList = Optional.ofNullable(originPage.getRecords()).orElse(new ArrayList<>());
        List<T> targetList = originList.stream().map(converter).collect(Collectors.toList());
        return new PageResult<>(originPage.getTotal(), targetList,
                originPage.getCurrent(), originPage.getSize(), originPage.getPages());
    }

    public static <T, F> PageResult<T> convert(PageResult<F> originPage, Function<F, T> converter) {
        List<F> originList = Optional.ofNullable(originPage.getList()).orElse(new ArrayList<>());
        List<T> targetList = originList.stream().map(converter).collect(Collectors.toList());
        return new PageResult<>(originPage.getTotal(), targetList,
                originPage.getCurrent(), originPage.getPageSize(), originPage.getTotalPage());
    }

}
