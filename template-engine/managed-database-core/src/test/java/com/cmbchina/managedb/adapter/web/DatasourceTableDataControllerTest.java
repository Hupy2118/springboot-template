package com.cmbchina.managedb.adapter.web;

import com.cmbchina.managedb.adapter.web.application.DatasourceTableDataController;
import com.cmbchina.managedb.adapter.web.security.ServiceKeyFilter;
import com.cmbchina.managedb.application.DatasourceTableDataService;
import com.cmbchina.managedb.application.view.TableDataView;
import com.cmbchina.managedb.domain.TableDataPage;
import com.cmbchina.managedb.domain.TableDataQuery;
import com.cmbchina.managedb.domain.TableMetadata;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 使用真正 SSE 输出验证查询合同、严格输入和服务密钥，不访问真实数据库。
 */
class DatasourceTableDataControllerTest {
    private static final String APP = "12ab34cd56ef", TABLE = APP + "_orders";
    private static final String URL = "/managed-database/datasources/" + APP + "/tables/" + TABLE + "/data/run";
    private DatasourceTableDataService service;
    private ObjectMapper mapper;
    private MockMvc mvc;

    /**
     * 只装配 Controller、JSON 解析和原认证过滤器。
     */
    @BeforeEach
    void setUp() {
        service = mock(DatasourceTableDataService.class);
        mapper = new ObjectMapper().findAndRegisterModules();
        mvc = MockMvcBuilders.standaloneSetup(new DatasourceTableDataController(service, mapper))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .addFilters(new ServiceKeyFilter("test-only-key", mapper)).build();
    }

    /**
     * 解析 AG-UI data，要求消息、状态和结束事件齐全，不只检查 HTTP 200。
     */
    private List<JsonNode> events(String props) throws Exception {
        MvcResult pending = mvc.perform(post(URL).servletPath(URL).header("Authorization", "Bearer test-only-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"threadId\":\"t\",\"runId\":\"r\",\"messages\":[],\"forwardedProps\":" + props + "}"))
                .andExpect(request().asyncStarted()).andExpect(header().string("Cache-Control", "no-store")).andReturn();
        String body = mvc.perform(asyncDispatch(pending)).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)).andReturn().getResponse().getContentAsString();
        List<JsonNode> result = new ArrayList<>();
        for (String line : body.split("\\r?\\n")) {
            if (line.startsWith("data:")) {
                result.add(mapper.readTree(line.substring(5)));
            }
        }
        assertEquals("RUN_STARTED", result.get(0).path("type").asText());
        assertEquals("RUN_FINISHED", result.get(result.size() - 1).path("type").asText());
        for (String type : Arrays.asList("TEXT_MESSAGE_START", "TEXT_MESSAGE_CONTENT", "TEXT_MESSAGE_END", "STEP_STARTED", "STATE_SNAPSHOT",
                "STEP_FINISHED")) {
            assertTrue(result.stream().anyMatch(event -> type.equals(event.path("type").asText())));
        }
        return result;
    }

    /**
     * 缺省分页调用同一查询意图，空表保留字段和 hasMore。
     */
    @Test
    void returnsActualPageContract() throws Exception {
        when(service.query(eq(APP), eq(TABLE), any())).thenAnswer(call -> {
            TableDataQuery query = call.getArgument(2);
            assertEquals(1, query.getPageNumber());
            assertEquals(20, query.getPageSize());
            TableMetadata table = new TableMetadata(TABLE, "订单",
                    Collections.singletonList(new TableMetadata.Column("id", "bigint", false, true, true, null, "主键")));
            return new TableDataView(APP, table, query,
                    new TableDataPage(Collections.emptyList(), Collections.singletonMap("id", "TEXT"), false, false));
        });
        List<JsonNode> result = events("{\"action\":\"QUERY\"}");
        JsonNode payload = result.get(result.size() - 1).path("result");
        assertEquals("SUCCEEDED", payload.path("status").asText());
        assertEquals(TABLE, payload.path("tableName").asText());
        assertEquals(1, payload.path("columns").size());
        assertEquals(0, payload.path("rows").size());
        assertFalse(payload.path("filteringSupported").asBoolean());
        assertTrue(result.stream().anyMatch(event -> "TABLE_DATA_RESULT".equals(event.path("name").asText())));
    }

    /**
     * 非空预留条件返回明确不支持，不能悄悄变成无条件查询。
     */
    @Test
    void nonEmptyFilterFailsExplicitly() throws Exception {
        when(service.query(eq(APP), eq(TABLE), any())).thenAnswer(call -> {
            ((TableDataQuery)call.getArgument(2)).requireUnfiltered();
            return null;
        });
        List<JsonNode> result =
                events("{\"action\":\"QUERY\",\"query\":{\"filter\":{\"logic\":\"AND\",\"conditions\":[{\"field\":\"leave_type\",\"operator\":\"EQ\",\"values\":[\"年假\"]}]}}}");
        assertEquals("QUERY_FILTER_UNSUPPORTED", result.get(result.size() - 1).path("result").path("code").asText());
        assertTrue(result.stream().anyMatch(event -> "TABLE_DATA_ERROR".equals(event.path("name").asText())));
    }

    /**
     * 非整数、超限、显式 null 和未知 SQL 参数在应用服务之前拒绝。
     */
    @Test
    void rejectsInvalidAndUnknownQueryFields() throws Exception {
        for (String query : Arrays.asList("{\"pageSize\":101}", "{\"pageNumber\":\"1\"}", "{\"pageSize\":1.5}", "{\"pageSize\":null}",
                "{\"sql\":\"SELECT 1\"}", "{\"filter\":null}", "null")) {
            List<JsonNode> result = events("{\"action\":\"QUERY\",\"query\":" + query + "}");
            assertEquals("INVALID_ARGUMENT", result.get(result.size() - 1).path("result").path("code").asText());
        }
        verifyNoInteractions(service);
    }

    /**
     * 服务异常只返回安全错误并完整结束生命周期，不透出原始连接信息。
     */
    @Test
    void unexpectedFailureIsSanitized() throws Exception {
        when(service.query(eq(APP), eq(TABLE), any())).thenThrow(new IllegalStateException("password=private"));
        List<JsonNode> result = events("{\"action\":\"QUERY\"}");
        assertEquals("TABLE_DATA_QUERY_FAILED", result.get(result.size() - 1).path("result").path("code").asText());
        assertFalse(result.toString().contains("private"));
    }

    /**
     * 鉴权失败在解析查询之前返回 401，不运行任何业务。
     */
    @Test
    void requiresServiceKey() throws Exception {
        mvc.perform(post(URL).servletPath(URL).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
}
