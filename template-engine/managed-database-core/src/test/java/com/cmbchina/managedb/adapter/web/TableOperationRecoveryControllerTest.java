package com.cmbchina.managedb.adapter.web;
import com.cmbchina.managedb.domain.Actor;

import com.cmbchina.managedb.adapter.web.application.TableOperationRecoveryController;
import com.cmbchina.managedb.adapter.web.security.ServiceKeyFilter;
import com.cmbchina.managedb.application.TableOperationRecoveryService;
import com.cmbchina.managedb.application.TableOperationService;
import com.cmbchina.managedb.application.view.TableOperationView;
import com.cmbchina.managedb.domain.ResourceErrorCode;
import com.cmbchina.managedb.domain.TableOperation;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证确认、持久化状态与错误均处于 AG-UI 生命周期，且继续受服务密钥保护。
 */
class TableOperationRecoveryControllerTest {
    private static final String APP = "b0ddb23acc91";
    private static final String URL = "/managed-database/datasources/" + APP + "/table-operations/101/recovery";
    private TableOperationRecoveryService recovery;
    private TableOperationService operations;
    private MockMvc mvc;
    private ObjectMapper mapper;

    /**
     * 仅装配协议和过滤器，不启动服务或连接数据库。
     */
    @BeforeEach
    void setUp() {
        recovery = mock(TableOperationRecoveryService.class);
        operations = mock(TableOperationService.class);
        mapper = new ObjectMapper().findAndRegisterModules();
        mvc = MockMvcBuilders.standaloneSetup(new TableOperationRecoveryController(recovery, operations))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .addFilters(new ServiceKeyFilter("test-only-key", mapper)).build();
    }

    /**
     * 解析真实 SSE data，验证事件顺序而不把 HTTP 成功当作处置完成。
     */
    private List<JsonNode> events(String props) throws Exception {
        MvcResult pending = mvc.perform(post(URL).servletPath(URL).header("Authorization", "Bearer test-only-key")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"threadId\":\"t\",\"runId\":\"r\",\"forwardedProps\":" + props + "}"))
                .andExpect(request().asyncStarted()).andReturn();
        String body = mvc.perform(asyncDispatch(pending)).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)).andReturn().getResponse().getContentAsString();
        List<JsonNode> result = new ArrayList<>();
        for (String line : body.split("\\r?\\n")) {
            if (line.startsWith("data:")) {
                result.add(mapper.readTree(line.substring(5)));
            }
        }
        assertEquals("RUN_STARTED", result.get(0).path("type").asText());
        return result;
    }

    /**
     * 预览只展示确认信息，不能申请或释放。
     */
    @Test
    void previewRequiresConfirmationWithoutMutation() throws Exception {
        TableOperation op = TableOperation.start(101L, APP, 1L, "orders", "CREATE TABLE orders(id bigint)", TableOperation.Mode.CREATE, false, Actor.system());
        when(operations.detail(APP, 101L)).thenReturn(new TableOperationView(op));
        List<JsonNode> result = events("{\"action\":\"PREVIEW\"}");
        assertTrue(result.stream().anyMatch(e -> "ABANDON_CONFIRMATION_REQUIRED".equals(e.path("name").asText())));
        assertEquals("RUN_FINISHED", result.get(result.size() - 1).path("type").asText());
        verifyNoInteractions(recovery);
    }

    /**
     * UNKNOWN 申请受理仍显示人工待处置，不误报占用已释放。
     */
    @Test
    void confirmedUnknownShowsManualRequired() throws Exception {
        TableOperation op = TableOperation.start(101L, APP, 1L, "orders", "CREATE TABLE orders(id bigint)", TableOperation.Mode.CREATE, false, Actor.system());
        op.finish(TableOperation.Status.UNKNOWN, "EXECUTION_UNKNOWN");
        op.requestAbandon("停止", Actor.system());
        when(recovery.requestAbandon(APP, 101L, "停止", true, Actor.system())).thenReturn(new TableOperationView(op));
        List<JsonNode> result = events("{\"action\":\"CONFIRM\",\"reason\":\"停止\",\"confirmed\":true}");
        assertEquals("MANUAL_REQUIRED", result.get(result.size() - 1).path("result").path("recoveryState").asText());
        assertTrue(result.stream().anyMatch(e -> "STATE_SNAPSHOT".equals(e.path("type").asText())));
    }

    /**
     * 业务失败以 RUN_ERROR 结束，不能继续发 RUN_FINISHED。
     */
    @Test
    void rejectedConfirmationEndsWithRunError() throws Exception {
        when(recovery.requestAbandon(APP, 101L, "停止", false, Actor.system())).thenThrow(new BizException(ResourceErrorCode.INVALID_ARGUMENT));
        List<JsonNode> result = events("{\"action\":\"CONFIRM\",\"reason\":\"停止\"}");
        assertEquals("RUN_ERROR", result.get(result.size() - 1).path("type").asText());
        assertFalse(result.stream().anyMatch(e -> "RUN_FINISHED".equals(e.path("type").asText())));
    }

    /**
     * 未认证请求在进入处置用例前被拒绝。
     */
    @Test
    void credentialsAreRequired() throws Exception {
        mvc.perform(post(URL).servletPath(URL).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(recovery, operations);
    }
}
