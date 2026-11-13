package com.cmbchina.managedb.adapter.web.application;

import com.cmb.bee.web.annotations.BeeResponseBody;
import com.cmbchina.managedb.adapter.web.request.AuthorizationSeedRequest;
import com.cmbchina.managedb.application.AuthorizationSeedService;
import com.cmbchina.managedb.application.view.DataOperationView;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;

/**
 * Bearer 保护的 SaaS 服务间合同，Studio 产品交互仍由其后端负责。
 */
@RestController
@BeeResponseBody
@RequiredArgsConstructor
@RequestMapping(value = "/managed-database/datasources/{datasourceKey}/data-operations", produces = MediaType.APPLICATION_JSON_VALUE)
public class DataOperationController {
    private final AuthorizationSeedService service;
    private final ObjectMapper mapper;

    /**
     * 202 表示受理或返回原受理记录；前置鉴权在读取请求体之前执行。
     */
    @PostMapping(value = "/batch", consumes = MediaType.APPLICATION_JSON_VALUE)
    public DataOperationView.Receipt submit(@PathVariable String datasourceKey,
                                            HttpServletRequest request) throws IOException {
        AuthorizationSeedRequest input = AuthorizationSeedRequest.parse(datasourceKey, request.getInputStream(), mapper);
        return service.submit(input.getIdempotencyKey(), input.getPayload());
    }

    /**
     * 专用幂等查找路由，完全只读。
     */
    @GetMapping("/by-key/{idempotencyKey}")
    public DataOperationView byKey(@PathVariable String datasourceKey, @PathVariable String idempotencyKey) {
        return service.byKey(datasourceKey, idempotencyKey);
    }

    /**
     * 读取指定资源下的安全状态摘要。
     */
    @GetMapping("/{operationId}")
    public DataOperationView detail(@PathVariable String datasourceKey, @PathVariable long operationId) {
        return service.detail(datasourceKey, operationId);
    }
}
