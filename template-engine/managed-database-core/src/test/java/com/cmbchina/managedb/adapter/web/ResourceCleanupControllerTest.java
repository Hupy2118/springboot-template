package com.cmbchina.managedb.adapter.web;

import com.cmbchina.managedb.domain.Actor;

import com.cmbchina.managedb.adapter.web.application.ResourceCleanupController;
import com.cmbchina.managedb.adapter.web.security.ServiceKeyFilter;
import com.cmbchina.managedb.application.CleanupConfirmation;
import com.cmbchina.managedb.application.ResourceCleanupService;
import com.cmbchina.managedb.application.view.ResourceCleanupPreview;
import com.cmbchina.managedb.domain.ResourceErrorCode;
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
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证新增清理 API 受密钥保护，成功与失败均发出完整 AG-UI 生命周期。
 */
class ResourceCleanupControllerTest {
    private static final String APP = "12ab34cd56ef", URL = "/managed-database/datasources/" + APP + "/cleanup/run";
    private ResourceCleanupService service;
    private MockMvc mvc;
    private ObjectMapper mapper;

    /**
     * 只装配协议和过滤器，不启动应用、不连接数据库。
     */
    @BeforeEach
    void setUp() {
        service = mock(ResourceCleanupService.class);
        mapper = new ObjectMapper().findAndRegisterModules();
        mvc = MockMvcBuilders.standaloneSetup(new ResourceCleanupController(service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).addFilters(new ServiceKeyFilter("test-only-key", mapper))
                .build();
    }

    /**
     * 读取真实 SSE 输出，检查完整生命周期而不是只断言 HTTP 成功。
     */
    private List<JsonNode> events(String props) throws Exception {
        MvcResult pending = mvc.perform(
                        post(URL).servletPath(URL).header("Authorization", "Bearer test-only-key").contentType(MediaType.APPLICATION_JSON)
                                .content("{\"threadId\":\"t\",\"runId\":\"r\",\"forwardedProps\":" + props + "}")).andExpect(request().asyncStarted())
                .andReturn();
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
        assertTrue(result.stream().anyMatch(e -> "TEXT_MESSAGE_END".equals(e.path("type").asText())));
        assertTrue(result.stream().anyMatch(e -> "STATE_SNAPSHOT".equals(e.path("type").asText())));
        return result;
    }

    /**
     * 预览仅返回确认清单，不触发清理提交。
     */
    @Test
    void previewsWithoutMutation() throws Exception {
        when(service.preview(APP, "DATASOURCE", null)).thenReturn(
                new ResourceCleanupPreview(APP, "DATASOURCE", Collections.singletonList("orders"), String.join("", Collections.nCopies(64, "a"))));
        List<JsonNode> result = events("{\"action\":\"PREVIEW\",\"scope\":\"DATASOURCE\"}");
        assertTrue(result.stream().anyMatch(e -> "RESOURCE_CLEANUP_PREVIEW".equals(e.path("name").asText())));
        verify(service).preview(APP, "DATASOURCE", null);
        verifyNoMoreInteractions(service);
    }

    /**
     * 业务拒绝也产生结果和最终状态，不把异常变成不完整事件流。
     */
    @Test
    void businessFailureCompletesLifecycle() throws Exception {
        when(service.confirm(eq(APP), eq("DATASOURCE"), isNull(),
                any(CleanupConfirmation.class), eq(Actor.system()))).thenThrow(
                new BizException(ResourceErrorCode.INVALID_ARGUMENT));
        List<JsonNode> result = events("{\"action\":\"CONFIRM\",\"scope\":\"DATASOURCE\",\"previewHash\":\"x\"}");
        assertEquals("INVALID_ARGUMENT", result.get(result.size() - 1).path("result").path("code").asText());
        assertTrue(result.stream().anyMatch(e -> "RESOURCE_CLEANUP_ERROR".equals(e.path("name").asText())));
    }

    /**
     * 操作 ID 溢出和非法格式在协议边界拒绝，不能查任意记录。
     */
    @Test
    void rejectsInvalidOperationId() throws Exception {
        List<JsonNode> result = events("{\"action\":\"STATUS\",\"operationId\":\"9999999999999999999\"}");
        assertEquals("INVALID_ARGUMENT", result.get(result.size() - 1).path("result").path("code").asText());
        verifyNoInteractions(service);
    }

    /**
     * 认证失败发生在业务用例之前。
     */
    @Test
    void requiresServiceKey() throws Exception {
        mvc.perform(post(URL).servletPath(URL).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
}
