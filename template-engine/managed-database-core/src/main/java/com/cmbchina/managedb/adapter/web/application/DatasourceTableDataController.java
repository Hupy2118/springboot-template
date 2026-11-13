package com.cmbchina.managedb.adapter.web.application;

import com.cmb.bee.commons.exceptions.BizException;
import com.cmbchina.managedb.adapter.web.request.TableDataRequest;
import com.cmbchina.managedb.application.DatasourceTableDataService;
import com.cmbchina.managedb.domain.ResourceErrorCode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;


/**
 * 表数据独立 AG-UI 查询入口，服务密钥鉴权及完整生命周期与现有产品流一致。
 */
@RestController
@RequiredArgsConstructor
public class DatasourceTableDataController {
    private final DatasourceTableDataService data;
    private final ObjectMapper mapper;

    /**
     * 一次只查询一页，业务失败也返回明确错误、状态和运行结束。
     */
    @PostMapping(value = "/managed-database/datasources/{datasourceKey}/tables/{tableName}/data/run",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter run(@PathVariable String datasourceKey, @PathVariable String tableName,
                          @RequestBody JsonNode input, HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", "no-store");
        SseEmitter stream = new SseEmitter(60000L);
        String thread = identifier(input, "threadId"), run = identifier(input, "runId");
        send(stream, "RUN_STARTED", "threadId", thread, "runId", run);
        String message = UUID.randomUUID().toString();
        send(stream, "TEXT_MESSAGE_START", "messageId", message, "role", "assistant");
        send(stream, "TEXT_MESSAGE_CONTENT", "messageId", message, "delta", "正在读取当前数据表的一页数据。");
        send(stream, "TEXT_MESSAGE_END", "messageId", message);
        send(stream, "STEP_STARTED", "stepName", "table-data-query");
        Object result;
        String event;
        try {
            TableDataRequest request = parse(input);
            TableDataRequest.Props props = request.getForwardedProps();
            if (props == null || !"QUERY".equals(props.getAction()) || props.getQuery() == null) {
                throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
            }
            result = data.query(datasourceKey, tableName, props.getQuery().toDomain());
            event = "TABLE_DATA_RESULT";
        } catch (BizException failure) {
            result = error(failure.getErrorCode().getErrorCodeStr(), failure.getErrorCode().getErrorMessage());
            event = "TABLE_DATA_ERROR";
        } catch (RuntimeException failure) {
            result = error("TABLE_DATA_QUERY_FAILED", "表数据读取失败，请检查资源状态后重新查询。");
            event = "TABLE_DATA_ERROR";
        }
        send(stream, "CUSTOM", "name", event, "value", result);
        send(stream, "STATE_SNAPSHOT", "snapshot", Collections.singletonMap("tableData", result));
        send(stream, "STEP_FINISHED", "stepName", "table-data-query");
        send(stream, "RUN_FINISHED", "threadId", thread, "runId", run, "result", result);
        stream.complete();
        return stream;
    }

    /**
     * 拒绝未知业务字段、浮点及字符串分页；顶层标准 AG-UI 字段可正常透传忽略。
     */
    private TableDataRequest parse(JsonNode input) {
        if (input == null || !input.isObject()) {
            throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
        }
        JsonNode query = input.path("forwardedProps").path("query");
        for (String key : Arrays.asList("pageNumber", "pageSize")) {
            if (query.has(key) && (!query.get(key).isIntegralNumber() || !query.get(key).canConvertToInt())) {
                throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
            }
        }
        try {
            return mapper.readerFor(TableDataRequest.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT).readValue(input.traverse(mapper));
        } catch (IOException failure) {
            throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
        }
    }

    /**
     * 错误只包含稳定业务码和脱敏文案，不携带真实 SQL 或原始驱动异常。
     */
    private static Map<String, Object> error(String code, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "FAILED");
        result.put("code", code);
        result.put("message", message);
        return result;
    }

    /**
     * 事件关联标识不参与鉴权、SQL 或数据库资源定位。
     */
    private static String identifier(JsonNode input, String key) {
        JsonNode node = input == null ? null : input.get(key);
        String value = node != null && node.isTextual() ? node.asText() : null;
        return value != null && !value.trim().isEmpty() && value.length() <= 128 ? value : UUID.randomUUID().toString();
    }

    /**
     * 标准 SSE data 承载 AG-UI，不记录业务数据或凭据。
     */
    private static void send(SseEmitter stream, String type, Object... pairs) throws IOException {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        for (int i = 0; i < pairs.length; i += 2) {
            event.put((String)pairs[i], pairs[i + 1]);
        }
        stream.send(SseEmitter.event().data(event, MediaType.APPLICATION_JSON));
    }
}
