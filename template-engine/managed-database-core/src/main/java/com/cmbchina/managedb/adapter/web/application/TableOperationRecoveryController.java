package com.cmbchina.managedb.adapter.web.application;

import com.cmb.bee.commons.exceptions.BizException;
import com.cmb.bee.web.annotations.BeeResponseBody;
import com.cmbchina.managedb.adapter.web.request.OperationRecoveryRequest;
import com.cmbchina.managedb.adapter.web.security.ActorFilter;
import com.cmbchina.managedb.application.TableOperationRecoveryService;
import com.cmbchina.managedb.application.TableOperationService;
import com.cmbchina.managedb.application.view.TableOperationView;
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
 * 受服务密钥保护的 Studio 后端入口，不开放绕过核对的强制释放接口。
 */
@RestController
@RequiredArgsConstructor
@BeeResponseBody
public class TableOperationRecoveryController {
    private final TableOperationRecoveryService recovery;
    private final TableOperationService operations;

    /**
     * 每次交互具有完整 AG-UI 生命周期；STATUS 可在断线后恢复管理状态。
     */
    @PostMapping(value = "/managed-database/datasources/{datasourceKey}/table-operations/{operationId}/recovery",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter recover(@PathVariable String datasourceKey, @PathVariable long operationId,
                              @RequestBody OperationRecoveryRequest request, HttpServletRequest httpRequest) throws IOException {
        Actor actor = (Actor) httpRequest.getAttribute(ActorFilter.ACTOR_ATTRIBUTE);
        if (actor == null) {
            actor = Actor.system();
        }
        SseEmitter stream = new SseEmitter(30000L);
        String threadId = identifier(request.getThreadId()), runId = identifier(request.getRunId());
        send(stream, "RUN_STARTED", "threadId", threadId, "runId", runId);
        try {
            OperationRecoveryRequest.Props props = request.getForwardedProps();
            if (props == null || props.getAction() == null) {
                throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
            }
            send(stream, "STEP_STARTED", "stepName", "operation-recovery");
            TableOperationView view;
            switch (props.getAction()) {
                case "PREVIEW":
                    view = operations.detail(datasourceKey, operationId);
                    send(stream, "CUSTOM", "name", "ABANDON_CONFIRMATION_REQUIRED", "value",
                            Collections.singletonMap("message", "已执行的变更不会回滚，数据不会自动恢复；未知结果需核对后才能释放占用。"));
                    break;
                case "CONFIRM":
                    view = recovery.requestAbandon(datasourceKey, operationId, props.getReason(), Boolean.TRUE.equals(props.getConfirmed()), actor);
                    break;
                case "STATUS":
                    view = operations.detail(datasourceKey, operationId);
                    break;
                default:
                    throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
            }
            send(stream, "STATE_SNAPSHOT", "snapshot", Collections.singletonMap("operation", view));
            send(stream, "STEP_FINISHED", "stepName", "operation-recovery");
            send(stream, "RUN_FINISHED", "threadId", threadId, "runId", runId,
                    "result", Collections.singletonMap("recoveryState", view.getExecutionResult().getRecoveryState()));
            stream.complete();
        } catch (BizException e) {
            send(stream, "RUN_ERROR", "code", e.getErrorCode().getErrorCodeStr(), "message", e.getErrorCode().getErrorMessage());
            stream.complete();
        } catch (RuntimeException e) {
            send(stream, "RUN_ERROR", "code", "INTERNAL_ERROR", "message", "处置结果未确认，请查询原操作，勿自动重试写入");
            stream.complete();
        }
        return stream;
    }

    /**
     * 仅用于事件关联，不作为身份、授权或幂等凭据。
     */
    private static String identifier(String value) {
        return value != null && !value.trim().isEmpty() && value.length() <= 128 ? value : UUID.randomUUID().toString();
    }

    /**
     * 使用 SSE data 承载 AG-UI 事件，避免把驱动错误和请求原文发给前端。
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
