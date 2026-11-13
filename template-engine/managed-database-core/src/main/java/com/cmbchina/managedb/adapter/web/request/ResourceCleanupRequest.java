package com.cmbchina.managedb.adapter.web.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

/**
 * 清理 AG-UI 输入，不接受任意 SQL、Schema 或数据库挂载。
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResourceCleanupRequest {
    private String threadId;
    private String runId;
    private Props forwardedProps;

    /**
     * PREVIEW/CONFIRM/STATUS 分开，确认只引用服务端生成的清单指纹。
     */
    @Getter
    @Setter
    public static class Props {
        private String action;
        private String scope;
        private String tableName;
        private String previewHash;
        private Boolean confirmed;
        private Boolean allowDataLoss;
        private String operationId;
    }
}
