package com.cmbchina.managedb.domain;

import com.cmb.bee.commons.exceptions.BizException;
import com.cmbchina.managedb.domain.valueobject.DatasourceKey;
import lombok.Getter;

/**
 * 数据源资源注册聚合，固定资源与挂载的绑定，不保存 Schema 或连接凭据。
 */
@Getter
public final class DatasourceResource {
    private final DatasourceKey datasourceKey;
    private final long datasourceId;
    private final String appKey;
    private final String appName;
    private final String createdBy;
    private final String creator;
    private ResourceStatus status;
    private final boolean deleted;
    private Long activeOperationId;

    /**
     * 固定创建或持久化恢复得到的状态，不对外开放 setter。
     */
    private DatasourceResource(DatasourceKey datasourceKey, long datasourceId, String appKey, String appName,
                               String createdBy, String creator,
                               ResourceStatus status, boolean deleted, Long activeOperationId) {
        if (datasourceKey == null || datasourceId <= 0 || status == null) {
            throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
        }
        validateOptionalText(appKey, 64);
        validateOptionalText(appName, 255);
        this.appKey = appKey;
        this.appName = appName;
        this.createdBy = createdBy;
        this.creator = creator;
        this.datasourceKey = datasourceKey;
        this.datasourceId = datasourceId;
        this.status = status;
        this.deleted = deleted;
        this.activeOperationId = activeOperationId;
    }

    /**
     * 创建待持久化注册记录，尚不代表目标库存在用户表；创建人记触发注册的最终用户。
     */
    public static DatasourceResource register(DatasourceKey datasourceKey, long datasourceId, String appKey, String appName,
                                              Actor actor) {
        validateRegistration(appKey, appName);
        return new DatasourceResource(datasourceKey, datasourceId, appKey, appName,
                actor.getUserId(), actor.getUserName(), ResourceStatus.REGISTERED, false, null);
    }

    /**
     * 仅供仓储恢复原状态，不把历史记录重置成注册成功；历史行无审计列时保持 null。
     */
    public static DatasourceResource restore(DatasourceKey datasourceKey, long datasourceId, String appKey, String appName,
                                             String createdBy, String creator,
                                             ResourceStatus status, boolean deleted, Long activeOperationId) {
        return new DatasourceResource(datasourceKey, datasourceId, appKey, appName, createdBy, creator,
                status, deleted, activeOperationId);
    }

    /**
     * 注册名称必填，应用 Key 可缺省；关联字段不作为资源唯一身份。
     */
    public static void validateRegistration(String appKey, String appName) {
        if (appName == null) {
            throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
        }
        validateOptionalText(appKey, 64);
        validateOptionalText(appName, 255);
    }

    /**
     * 持久化记录允许关联信息为空，已有资源不推测名称或伪造应用 Key。
     */
    private static void validateOptionalText(String text, int limit) {
        if (text != null && (text.trim().isEmpty() || !text.equals(text.trim())
                || text.codePointCount(0, text.length()) > limit)) {
            throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
        }
    }

    /**
     * 资源必须有效才能接受写入或启动检查。
     */
    public void requireActive() {
        if (deleted || status != ResourceStatus.REGISTERED) {
            throw new BizException(ResourceErrorCode.RESOURCE_NOT_FOUND);
        }
    }

    /**
     * 认领一个写操作，不允许覆盖未知或在途占用。
     */
    public void claim(long operationId) {
        requireActive();
        if (activeOperationId != null) {
            throw new BizException(ResourceErrorCode.RESOURCE_BUSY);
        }
        activeOperationId = operationId;
    }

    /**
     * 全部物理删除已证明后，只有占用当前资源的操作可以结束资源。
     */
    public void releaseResources(long operationId) {
        requireActive();
        releaseOperation(operationId);
        status = ResourceStatus.RELEASED;
    }

    /**
     * 只释放当前操作自己的占用。
     */
    public void releaseOperation(long operationId) {
        if (activeOperationId == null || activeOperationId != operationId) {
            throw new BizException(ResourceErrorCode.RESOURCE_BUSY);
        }
        activeOperationId = null;
    }
}
