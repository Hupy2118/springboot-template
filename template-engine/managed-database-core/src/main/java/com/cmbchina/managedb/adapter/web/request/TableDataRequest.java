package com.cmbchina.managedb.adapter.web.request;

import com.cmb.bee.commons.exceptions.BizException;
import com.cmbchina.managedb.domain.ResourceErrorCode;
import com.cmbchina.managedb.domain.TableDataQuery;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * AG-UI 输入只投射业务参数，query 及条件对象由适配器严格解析。
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class TableDataRequest {
    private String threadId, runId;
    private Props forwardedProps;

    /**
     * 当前只接受 QUERY 动作，业务输入中没有 SQL、Schema 或数据源编号。
     */
    @Getter
    @Setter
    public static class Props {
        private String action;
        private Query query = new Query();
    }

    /**
     * 缺省分页与空条件对应一期数据预览，显式 null 不能静默改为默认。
     */
    @Getter
    @Setter
    public static class Query {
        private Integer pageNumber = 1, pageSize = 20;
        private Filter filter = new Filter();

        /**
         * 在协议边界构造不可变领域意图，后续条件扩展不改变端口签名。
         */
        public TableDataQuery toDomain() {
            if (pageNumber == null || pageSize == null || filter == null || filter.getConditions() == null || filter.getConditions().size() > 20) {
                throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
            }
            List<TableDataQuery.Condition> conditions = new ArrayList<>();
            for (Condition condition : filter.getConditions()) {
                if (condition == null) {
                    throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
                }
                conditions.add(new TableDataQuery.Condition(condition.getField(), condition.getOperator(), condition.getValues()));
            }
            return new TableDataQuery(pageNumber, pageSize, new TableDataQuery.Filter(filter.getLogic(), conditions));
        }
    }

    /**
     * 条件组的稳定传输结构，一期仅支持空 AND 条件组。
     */
    @Getter
    @Setter
    public static class Filter {
        private String logic = "AND";
        private List<Condition> conditions = new ArrayList<>();
    }

    /**
     * 预留结构化条件，不接收 SQL 片段或任意表达式。
     */
    @Getter
    @Setter
    public static class Condition {
        private String field, operator;
        private List<String> values = new ArrayList<>();
    }
}
