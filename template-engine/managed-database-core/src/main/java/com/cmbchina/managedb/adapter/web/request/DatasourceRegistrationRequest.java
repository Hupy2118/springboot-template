package com.cmbchina.managedb.adapter.web.request;

import com.cmb.bee.commons.exceptions.BizException;
import com.cmbchina.managedb.domain.ResourceErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;

import java.util.Iterator;

/**
 * 注册仅接受应用名称及可选应用 Key，拒绝客户端指定资源 Key 或挂载。
 */
@Getter
public final class DatasourceRegistrationRequest {
    private final String appKey;
    private final String appName;

    /**
     * 保留已校验的原始文本，不用应用名称补造应用 Key。
     */
    private DatasourceRegistrationRequest(String appKey, String appName) {
        this.appKey = appKey;
        this.appName = appName;
    }

    /**
     * 在协议边界校验对象、允许字段、严格文本类型与长度。
     */
    public static DatasourceRegistrationRequest parse(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
        }
        Iterator<String> fields = body.fieldNames();
        while (fields.hasNext()) {
            String name = fields.next();
            if (!"appKey".equals(name) && !"appName".equals(name)) {
                throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
            }
        }
        return new DatasourceRegistrationRequest(text(body.get("appKey"), 64, false),
                text(body.get("appName"), 255, true));
    }

    /**
     * 可选 Key 缺失或 null 时保留为空，显式空白及错误类型不能当作缺省。
     */
    private static String text(JsonNode node, int limit, boolean required) {
        if (node == null || node.isNull()) {
            if (required) {
                throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
            }
            return null;
        }
        if (!node.isTextual()) {
            throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
        }
        String value = node.textValue();
        if (value.trim().isEmpty() || !value.equals(value.trim())
                || value.codePointCount(0, value.length()) > limit) {
            throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
        }
        return value;
    }
}
