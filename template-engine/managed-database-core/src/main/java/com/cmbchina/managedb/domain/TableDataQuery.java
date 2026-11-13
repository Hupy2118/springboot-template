package com.cmbchina.managedb.domain;

import com.cmb.bee.commons.exceptions.BizException;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 不可变的表数据查询意图，分页和条件不携带 SQL 或连接。
 */
@Getter
public final class TableDataQuery {
    private final int pageNumber, pageSize;
    private final Filter filter;

    /**
     * 校验分页并限制深分页，计算偏移前使用 long 防止溢出。
     */
    public TableDataQuery(int pageNumber, int pageSize, Filter filter) {
        if (pageNumber < 1 || pageSize < 1 || pageSize > 100 || (long)(pageNumber - 1) * pageSize > 100000 || filter == null) {
            throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
        }
        this.pageNumber = pageNumber;
        this.pageSize = pageSize;
        this.filter = filter;
    }

    /**
     * 返回已校验的偏移，不接受调用方直接指定无限偏移。
     */
    public long offset() {
        return (long)(pageNumber - 1) * pageSize;
    }

    /**
     * 一期明确拒绝非空筛选，不能把条件静默忽略后返回全表。
     */
    public void requireUnfiltered() {
        if (!filter.getConditions().isEmpty()) {
            throw new BizException(ResourceErrorCode.QUERY_FILTER_UNSUPPORTED);
        }
    }

    /**
     * 结构化条件扩展口，当前只约定 AND，不实现 WHERE 编译。
     */
    @Getter
    public static final class Filter {
        private final String logic;
        private final List<Condition> conditions;

        /**
         * 固定条件集合并限制输入规模，不能通过可变集合绕过一期限制。
         */
        public Filter(String logic, List<Condition> conditions) {
            if (!"AND".equals(logic) || conditions == null || conditions.size() > 20 || conditions.contains(null)) {
                throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
            }
            this.logic = logic;
            this.conditions = Collections.unmodifiableList(new ArrayList<>(conditions));
        }
    }

    /**
     * 为后续等值、范围和集合运算保留字段、操作符及文本值数组。
     */
    @Getter
    public static final class Condition {
        private final String field, operator;
        private final List<String> values;

        /**
         * 只保存有界结构意图，运算符能力和字段类型在后续编译层落实。
         */
        public Condition(String field, String operator, List<String> values) {
            if (field == null || field.isEmpty() || field.length() > 64 || operator == null || !operator.matches("[A-Z_]{1,24}")
                    || values == null || values.size() > 20) {
                throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
            }
            for (String value : values) {
                if (value == null || value.length() > 4096) {
                    throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
                }
            }
            this.field = field;
            this.operator = operator;
            this.values = Collections.unmodifiableList(new ArrayList<>(values));
        }
    }
}
