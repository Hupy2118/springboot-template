package com.cmbchina.managedb.adapter.web.request;

import com.cmb.bee.commons.exceptions.BizException;
import com.cmbchina.managedb.domain.ResourceErrorCode;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 本入口严格识别字符串与数组，不影响单表或全局 Jackson 合同。
 */
@Getter
public final class BatchOperationRequest {
    private final String mode;
    private final List<String> sqls;

    /**
     * 显式拒绝未知字段、错误类型及缺失值，禁止 Jackson 隐式类型转换。
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public BatchOperationRequest(JsonNode json) {
        if (json == null || !json.isObject() || !validate(json)) {
            throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
        }
        mode = json.get("mode").textValue();
        List<String> values = new ArrayList<>();
        for (JsonNode sql : json.get("sqls")) {
            if (!sql.isTextual()) {
                throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
            }
            values.add(sql.textValue());
        }
        sqls = Collections.unmodifiableList(values);
    }

    private boolean validate(JsonNode json) {
        return json.has("mode") && json.has("sqls") && json.get("mode").isTextual() && json.get("sqls").isArray();
    }
}
