package com.cmbchina.managedb.adapter.web.application;

import com.cmb.bee.web.annotations.BeeResponseBody;
import com.cmbchina.managedb.adapter.web.request.BatchOperationRequest;
import com.cmbchina.managedb.adapter.web.request.CheckRequest;
import com.cmbchina.managedb.adapter.web.request.OperationRequest;
import com.cmbchina.managedb.adapter.web.security.ActorFilter;
import com.cmbchina.managedb.application.BatchTableOperationService;
import com.cmbchina.managedb.application.TableOperationService;
import com.cmbchina.managedb.application.TableReadinessService;
import com.cmbchina.managedb.application.view.TableOperationReceipt;
import com.cmbchina.managedb.application.view.TableOperationView;
import com.cmbchina.managedb.application.view.TableReadinessView;
import com.cmbchina.managedb.domain.Actor;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * 普通 JSON 表操作及启动门禁，协议层不访问仓储或数据库连接。
 */
@RestController
@RequiredArgsConstructor
@BeeResponseBody
@RequestMapping(value = "/managed-database/datasources/{datasourceKey}", produces = MediaType.APPLICATION_JSON_VALUE)
public class TableSynchronizationController {
    private final TableOperationService operations;
    private final TableReadinessService readiness;
    private final BatchTableOperationService batches;

    /**
     * 受理创建或明确授权的数据丢弃重建。
     *
     * <p>Bee 框架 @BeeResponseBody 统一返回 HTTP 200，@ResponseStatus 设置的状态码
     * 在响应 commit 后 apply 而失效，因此不再使用 @ResponseStatus；客户端以 200 判断成功。
     */
    @PostMapping(value = "/table-operations", consumes = MediaType.APPLICATION_JSON_VALUE)
    public TableOperationReceipt submit(@PathVariable String datasourceKey, @RequestBody OperationRequest request, HttpServletRequest httpRequest) {
        Actor actor = (Actor)httpRequest.getAttribute(ActorFilter.ACTOR_ATTRIBUTE);
        return operations.submit(datasourceKey, request.getMode(), request.getSql(),
                Boolean.TRUE.equals(request.getAllowDataLoss()), actor == null ? Actor.system() : actor);
    }

    /**
     * 全批次预检后返回统一异步回执；202 不代表物理表已建成。
     */
    @PostMapping(value = "/table-operations/batch", consumes = MediaType.APPLICATION_JSON_VALUE)
    public TableOperationReceipt batch(@PathVariable String datasourceKey, @RequestBody BatchOperationRequest request,
                                       HttpServletRequest httpRequest) {
        Actor actor = (Actor)httpRequest.getAttribute(ActorFilter.ACTOR_ATTRIBUTE);
        return batches.submit(datasourceKey, request.getMode(), request.getSqls(), actor);
    }
    /** 查询原记录，不触发重放。 */
    /**
     * 查询原记录，不触发重放。
     */
    @GetMapping("/table-operations/{operationId}")
    public TableOperationView detail(@PathVariable String datasourceKey, @PathVariable long operationId) {
        return operations.detail(datasourceKey, operationId);
    }

    /**
     * 查询最近保留历史，便于响应丢失和页面恢复。
     */
    @GetMapping("/table-operations")
    public List<TableOperationView> recent(@PathVariable String datasourceKey, @RequestParam(defaultValue = "20") int limit) {
        return operations.recent(datasourceKey, limit);
    }

    /**
     * 当前全部 SQL 的只读结构检查。
     */
    @PostMapping(value = "/tables/check", consumes = MediaType.APPLICATION_JSON_VALUE)
    public TableReadinessView check(@PathVariable String datasourceKey, @RequestBody CheckRequest request) {
        return readiness.check(datasourceKey, request.getSqls());
    }
}
