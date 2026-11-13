package com.cmbchina.managedb.adapter.web.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

/**
 * AG-UI RunAgentInput 的恢复入口投影，业务命令只从 forwardedProps 读取。
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class OperationRecoveryRequest {
    private String threadId;
    private String runId;
    private Props forwardedProps;

    /**
     * PREVIEW 展示确认，CONFIRM 申请放弃，STATUS 恢复持久化状态。
     */
    @Getter
    @Setter
    public static class Props {
        private String action;
        private String reason;
        private Boolean confirmed;
    }
}
