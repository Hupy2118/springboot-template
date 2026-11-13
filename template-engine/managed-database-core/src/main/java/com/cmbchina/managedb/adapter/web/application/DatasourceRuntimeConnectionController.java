package com.cmbchina.managedb.adapter.web.application;

import com.cmb.bee.web.annotations.BeeResponseBody;
import com.cmbchina.managedb.application.DatasourceRuntimeConnectionService;
import com.cmbchina.managedb.application.view.RuntimeConnectionView;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletResponse;

/**
 * 后端基础设施取密入口，产品交互仍由 Studio AG-UI 启动流程承接。
 */
@RestController
@RequiredArgsConstructor
@BeeResponseBody
@RequestMapping(value = "/managed-database/datasources/{datasourceKey}/runtime-connection", produces = MediaType.APPLICATION_JSON_VALUE)
public class DatasourceRuntimeConnectionController {
    private final DatasourceRuntimeConnectionService service;

    /**
     * 复用服务密钥过滤器，明确禁止中间层缓存连接响应。
     */
    @GetMapping
    public RuntimeConnectionView resolve(@PathVariable String datasourceKey, HttpServletResponse response) {
        // 连接凭据响应禁止中间层缓存，与取密入口的安全约定保持一致。
        response.setHeader("Cache-Control", "no-store");
        return service.resolve(datasourceKey);
    }
}
