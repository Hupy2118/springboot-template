package com.cmbchina.managedb.adapter.web;

import com.cmb.bee.commons.exceptions.BizException;
import com.cmb.bee.web.support.MockBeeResponseAdvice;
import com.cmbchina.managedb.adapter.web.application.DatasourceRuntimeConnectionController;
import com.cmbchina.managedb.adapter.web.security.ServiceKeyFilter;
import com.cmbchina.managedb.application.DatasourceRuntimeConnectionService;
import com.cmbchina.managedb.application.view.RuntimeConnectionView;
import com.cmbchina.managedb.common.exception.BaseExceptionHandler;
import com.cmbchina.managedb.domain.ResourceErrorCode;
import com.cmbchina.managedb.domain.RuntimeDatabaseConnection;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 可信内部取密接口必须经过鉴权且不能缓存，不启动 HTTP 服务。
 */
class DatasourceRuntimeConnectionControllerTest {
    private static final String APP = "12ab34cd56ef", PATH = "/managed-database/datasources/" + APP + "/runtime-connection";
    private DatasourceRuntimeConnectionService service;
    private MockMvc mvc;

    /**
     * 使用真实服务密钥过滤器与安全异常适配。
     */
    @BeforeEach
    void setUp() {
        service = mock(DatasourceRuntimeConnectionService.class);
        ObjectMapper mapper = new ObjectMapper();
        mvc = MockMvcBuilders.standaloneSetup(new DatasourceRuntimeConnectionController(service))
                .setControllerAdvice(new BaseExceptionHandler(), new MockBeeResponseAdvice()).setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .addFilters(new ServiceKeyFilter("test-key", mapper)).build();
    }

    /**
     * 鉴权后返回固定合同并禁止响应缓存。
     */
    @Test
    void returnsAuthenticatedNonCacheableConnection() throws Exception {
        when(service.resolve(APP)).thenReturn(
                new RuntimeConnectionView(APP, 2L, new RuntimeDatabaseConnection("localhost", 3306, "inner2", "demo", "test-password", "DISABLED")));
        mvc.perform(get(PATH).servletPath(PATH).header("Authorization", "Bearer test-key"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.body.datasourceKey").value(APP)).andExpect(jsonPath("$.body.datasourceId").value(2))
                .andExpect(jsonPath("$.body.username").value("demo")).andExpect(jsonPath("$.body.sslMode").value("DISABLED"));
    }

    /**
     * 无服务密钥不能执行取密用例。
     */
    @Test
    void rejectsUnauthenticatedRequests() throws Exception {
        mvc.perform(get(PATH).servletPath(PATH)).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    /**
     * 配置缺失返回可判定的资源错误，不伪造成功。
     */
    @Test
    void missingConfigurationIsServiceUnavailable() throws Exception {
        when(service.resolve(APP)).thenThrow(new BizException(ResourceErrorCode.RUNTIME_CONNECTION_NOT_CONFIGURED));
        mvc.perform(get(PATH).servletPath(PATH).header("Authorization", "Bearer test-key"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.returnCode").value("RUNTIME_CONNECTION_NOT_CONFIGURED"));
    }
}
