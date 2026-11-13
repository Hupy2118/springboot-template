package com.cmbchina.managedb.adapter.web.application;

import com.cmb.bee.commons.exceptions.BizException;
import com.cmbchina.managedb.adapter.web.request.ResourceCleanupRequest;
import com.cmbchina.managedb.adapter.web.security.ActorFilter;
import com.cmbchina.managedb.application.CleanupConfirmation;
import com.cmbchina.managedb.application.ResourceCleanupService;
import com.cmbchina.managedb.domain.Actor;
import com.cmbchina.managedb.domain.ResourceErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 资源清理独立 AG-UI 入口，沿用服务密钥，不接入 Studio 应用删除流程。
 */
@RestController
@RequiredArgsConstructor
public class ResourceCleanupController {
    private final ResourceCleanupService cleanup;

    /**
     * 每次请求完成消息、步骤、结果、状态与运行生命周期；受理不等于删表完成。
     */
    @PostMapping(value = "/managed-database/datasources/{datasourceKey}/cleanup/run",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter run(@PathVariable String datasourceKey, @RequestBody ResourceCleanupRequest request,
                          HttpServletRequest httpRequest) throws IOException {
        Actor actor = (Actor) httpRequest.getAttribute(ActorFilter.ACTOR_ATTRIBUTE);
        if (actor == null) {
            actor = Actor.system();
        }
        SseEmitter stream = new SseEmitter(30000L);
        String thread = identifier(request.getThreadId()), run = identifier(request.getRunId());
        send(stream, "RUN_STARTED", "threadId", thread, "runId", run);
        String message = UUID.randomUUID().toString();
        send(stream, "TEXT_MESSAGE_START", "messageId", message, "role", "assistant");
        send(stream, "TEXT_MESSAGE_CONTENT", "messageId", message, "delta", "正在处理内置数据库资源清理请求。");
        send(stream, "TEXT_MESSAGE_END", "messageId", message);
        send(stream, "STEP_STARTED", "stepName", "resource-cleanup");
        Object result;
        String event;
        try {
            ResourceCleanupRequest.Props props = request.getForwardedProps();
            if (props == null || props.getAction() == null) {
                throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
            }
            switch (props.getAction()) {
                case "PREVIEW":
                    result = cleanup.preview(datasourceKey, props.getScope(), props.getTableName());
                    event = "RESOURCE_CLEANUP_PREVIEW";
                    break;
                case "CONFIRM":
                    result = cleanup.confirm(datasourceKey, props.getScope(), props.getTableName(),
                            new CleanupConfirmation(props.getPreviewHash(),
                                    Boolean.TRUE.equals(props.getConfirmed()),
                                    Boolean.TRUE.equals(props.getAllowDataLoss())),
                            actor);
                    event = "RESOURCE_CLEANUP_ACCEPTED";
                    break;
                case "STATUS":
                    result = cleanup.status(datasourceKey, operationId(props.getOperationId()));
                    event = "RESOURCE_CLEANUP_STATUS";
                    break;
                default:
                    throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
            }
        } catch (BizException e) {
            result = error(e.getErrorCode().getErrorCodeStr(), e.getErrorCode().getErrorMessage());
            event = "RESOURCE_CLEANUP_ERROR";
        } catch (RuntimeException e) {
            result = error("EXECUTION_UNKNOWN", "请求结果未确认，请查询原操作记录，勿重复提交清理。");
            event = "RESOURCE_CLEANUP_ERROR";
        }
        send(stream, "CUSTOM", "name", event, "value", result);
        send(stream, "STATE_SNAPSHOT", "snapshot", Collections.singletonMap("cleanup", result));
        send(stream, "STEP_FINISHED", "stepName", "resource-cleanup");
        send(stream, "RUN_FINISHED", "threadId", thread, "runId", run, "result", result);
        stream.complete();
        return stream;
    }

    /**
     * 操作编号以十进制字符串传输，拒绝溢出、浮点或空值。
     */
    private static long operationId(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) {
            throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
        }
    }

    /**
     * 业务失败仍完整结束本次运行，不把 HTTP 200 当作清理成功。
     */
    private static Map<String, Object> error(String code, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "REJECTED");
        result.put("code", code);
        result.put("message", message);
        return result;
    }

    /**
     * 仅用于事件关联，不作为身份或幂等凭据。
     */
    private static String identifier(String value) {
        return value != null && !value.trim().isEmpty() && value.length() <= 128 ? value : UUID.randomUUID().toString();
    }

    /**
     * 标准 SSE data 承载 AG-UI 事件，不回显连接配置或底层异常。
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
