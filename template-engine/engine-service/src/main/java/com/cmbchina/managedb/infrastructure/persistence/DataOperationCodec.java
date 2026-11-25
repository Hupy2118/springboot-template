package com.cmbchina.managedb.infrastructure.persistence;

import com.cmb.bee.commons.exceptions.BizException;
import com.cmbchina.managedb.domain.AuthorizationSeedPayload;
import com.cmbchina.managedb.domain.DataOperation;
import com.cmbchina.managedb.domain.ResourceErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/** 独立序列化合同，读操作只解析安全结果。 */
@RequiredArgsConstructor
public final class DataOperationCodec {
    private final ObjectMapper mapper;

    /** 库内审计数据包含 Subject，只有受理写入会编码此字段。 */
    public String payload(AuthorizationSeedPayload payload) {
        return encode(payload.getRows());
    }

    /** 只写安全计数与固定错误码，另有独立状态列。 */
    public String result(DataOperation.Result result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("createdCount", result.getCreatedCount());
        body.put("existingCount", result.getExistingCount());
        if (result.getError() != null) {
            body.put("errorCode", result.getError().name());
        }
        return encode(body);
    }

    /** 从安全列恢复摘要，不加载业务载荷或尝试重放。 */
    public DataOperation.Result restore(String status, String result) {
        try {
            switch (DataOperation.Status.valueOf(status)) {
                case RUNNING:
                    return DataOperation.Result.running();
                case SUCCEEDED:
                    JsonNode counts = mapper.readTree(result);
                    return DataOperation.Result.committed(readCount(counts, "createdCount"),
                            readCount(counts, "existingCount"));
                case FAILED:
                    return DataOperation.Result.failed(ResourceErrorCode.valueOf(mapper.readTree(result).get("errorCode").textValue()));
                case UNKNOWN:
                    return DataOperation.Result.unknown();
                default:
                    throw new IllegalStateException();
            }
        } catch (RuntimeException | JsonProcessingException exception) {
            throw new BizException(ResourceErrorCode.INTERNAL_ERROR);
        }
    }

    /** 审计记录损坏时明确失败，禁止伪造成功计数。 */
    private static int readCount(JsonNode counts, String field) {
        JsonNode value = counts.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new BizException(ResourceErrorCode.INTERNAL_ERROR);
        }
        return value.intValue();
    }

    /** 编码异常不保留包含原始值的异常链。 */
    private String encode(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BizException(ResourceErrorCode.INTERNAL_ERROR);
        }
    }
}
