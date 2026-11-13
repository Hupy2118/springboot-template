package com.cmbchina.managedb.adapter.web;

import com.cmb.bee.commons.exceptions.BizException;
import com.cmb.bee.web.support.MockBeeResponseAdvice;
import com.cmbchina.managedb.adapter.web.application.DatasourceTableMetadataController;
import com.cmbchina.managedb.adapter.web.security.ServiceKeyFilter;
import com.cmbchina.managedb.application.DatasourceTableMetadataService;
import com.cmbchina.managedb.application.view.DatasourceTablesView;
import com.cmbchina.managedb.application.view.TableColumnsView;
import com.cmbchina.managedb.common.exception.BaseExceptionHandler;
import com.cmbchina.managedb.domain.ResourceErrorCode;
import com.cmbchina.managedb.domain.TableMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 查询接口复用真实服务鉴权和统一错误映射，不启动服务。
 */
class DatasourceTableMetadataControllerTest {
    private static final String APP = "b0ddb23acc91";
    private static final String BASE = "/managed-database/datasources/" + APP + "/tables";
    private DatasourceTableMetadataService service;
    private MockMvc mvc;

    /**
     * 使用真实 JSON 转换器、鉴权过滤器和业务异常处理。
     */
    @BeforeEach
    void setUp() {
        service = mock(DatasourceTableMetadataService.class);
        ObjectMapper mapper = new ObjectMapper();
        mvc = MockMvcBuilders.standaloneSetup(new DatasourceTableMetadataController(service))
                .setControllerAdvice(new BaseExceptionHandler(), new MockBeeResponseAdvice())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .addFilters(new ServiceKeyFilter("test-only-key", mapper)).build();
    }

    /**
     * 返回应用表摘要，不暴露挂载位置和连接配置。
     */
    @Test
    void listsTablesAsPlainJson() throws Exception {
        TableMetadata table = new TableMetadata("orders", "订单表", Collections.emptyList());
        when(service.tables(APP)).thenReturn(new DatasourceTablesView(APP, Collections.singletonList(table)));
        mvc.perform(get(BASE).servletPath(BASE).header("Authorization", "Bearer test-only-key"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.body.datasourceKey").value(APP))
                .andExpect(jsonPath("$.body.tables[0].tableName").value("orders"))
                .andExpect(jsonPath("$.body.tables[0].description").value("订单表"))
                .andExpect(jsonPath("$.body.datasourceId").doesNotExist()).andExpect(jsonPath("$.body.schema").doesNotExist());
    }

    /**
     * 字段保留真实可空、主键、自增及默认值语义。
     */
    @Test
    void returnsActualColumns() throws Exception {
        TableMetadata table = new TableMetadata("orders", "订单表", Collections.singletonList(
                new TableMetadata.Column("id", "bigint", false, true, true, null, "主键")));
        when(service.columns(APP, "orders")).thenReturn(new TableColumnsView(APP, table));
        String path = BASE + "/orders/columns";
        mvc.perform(get(path).servletPath(path).header("Authorization", "Bearer test-only-key"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.body.columns[0].name").value("id"))
                .andExpect(jsonPath("$.body.columns[0].primaryKey").value(true))
                .andExpect(jsonPath("$.body.columns[0].autoIncrement").value(true))
                .andExpect(jsonPath("$.body.columns[0].nullable").value(false));
    }

    /**
     * 未鉴权请求在用例执行前拒绝，不读取表目录。
     */
    @Test
    void rejectsUnauthenticatedRequests() throws Exception {
        mvc.perform(get(BASE).servletPath(BASE)).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    /**
     * 繁忙、读取失败与真实缺表都保留明确 HTTP 错误。
     */
    @Test
    void keepsBusyUnavailableAndNotFoundDistinct() throws Exception {
        when(service.tables(APP)).thenThrow(new BizException(ResourceErrorCode.RESOURCE_BUSY));
        mvc.perform(get(BASE).servletPath(BASE).header("Authorization", "Bearer test-only-key"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.returnCode").value("RESOURCE_BUSY"));
        doThrow(new BizException(ResourceErrorCode.DATABASE_UNAVAILABLE)).when(service).tables(APP);
        mvc.perform(get(BASE).servletPath(BASE).header("Authorization", "Bearer test-only-key"))
                .andExpect(status().isServiceUnavailable());
        when(service.columns(APP, "missing")).thenThrow(new BizException(ResourceErrorCode.RESOURCE_NOT_FOUND));
        String path = BASE + "/missing/columns";
        mvc.perform(get(path).servletPath(path).header("Authorization", "Bearer test-only-key"))
                .andExpect(status().isNotFound());
    }
}
