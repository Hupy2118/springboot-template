package com.cmbchina.managedb.adapter.web;

import com.cmb.bee.commons.exceptions.BizException;
import com.cmb.bee.web.support.MockBeeResponseAdvice;
import com.cmbchina.managedb.adapter.web.controller.DataOperationController;
import com.cmbchina.managedb.adapter.web.security.ServiceKeyFilter;
import com.cmbchina.managedb.application.AuthorizationSeedService;
import com.cmbchina.managedb.adapter.web.dto.DataOperationView;
import com.cmbchina.managedb.common.exception.BaseExceptionHandler;
import com.cmbchina.managedb.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.StreamUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static com.cmbchina.managedb.AuthorizationSeedFixture.*;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 验证真实解析、鉴权、路由和安全响应包装。 */
class DataOperationControllerTest {
    private static final String BASE = "/managed-database/datasources/" + KEY + "/data-operations";
    private final AuthorizationSeedService service = mock(AuthorizationSeedService.class);
    private MockMvc mvc;

    /** 独立装配 Web 合同，无需服务启动或真实数据库。 */
    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        mvc = MockMvcBuilders.standaloneSetup(new DataOperationController(service, mapper))
                .setControllerAdvice(new BaseExceptionHandler(), new MockBeeResponseAdvice())
                .addFilters(new ServiceKeyFilter("fixture-key", mapper)).build();
    }

    /** 200 只返回安全回执，两个 GET 仅返回摘要。 */
    @Test
    void returnsReceiptAndSafeLookup() throws Exception {
        when(service.submit(eq(IDEMPOTENCY_KEY), any(), isNull())).thenReturn(new DataOperationView.Receipt("12", DataOperation.Status.RUNNING));
        mvc.perform(submit(json(KEY))).andExpect(status().isOk())
                .andExpect(jsonPath("$.returnCode").value("SUC0000"))
                .andExpect(jsonPath("$.errorMsg").value(""))
                .andExpect(jsonPath("$.body.operationId").value("12"))
                .andExpect(jsonPath("$.body.status").value("RUNNING"))
                .andExpect(jsonPath("$.body.purpose").value("AUTHORIZATION_SEED_V1"))
                .andExpect(jsonPath("$.body.verified").doesNotExist());
        DataOperationView result = new DataOperationView(new DataOperation(12, KEY, 1, IDEMPOTENCY_KEY,
                DataOperation.Result.committed(5, 0)));
        when(service.byKey(KEY, IDEMPOTENCY_KEY)).thenReturn(result);
        when(service.detail(KEY, 12)).thenReturn(result);
        for (String path : new String[]{"/12", "/by-key/" + IDEMPOTENCY_KEY}) {
            mvc.perform(authorize(get(BASE + path), BASE + path)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.returnCode").value("SUC0000"))
                    .andExpect(jsonPath("$.errorMsg").value(""))
                    .andExpect(jsonPath("$.body.verified").value(true))
                    .andExpect(jsonPath("$.body.createdCount").value(5))
                    .andExpect(jsonPath("$.body.rows").doesNotExist())
                    .andExpect(content().string(not(containsString(SUBJECT))));
        }
    }

    /** 冻结示例经过真实解析和 Bee 包装，且调度信息不进入指纹。 */
    @Test
    void acceptsFrozenRetryExample() throws Exception {
        String retry;
        try (InputStream input = new ClassPathResource("authorization-seed-retry.json").getInputStream()) {
            retry = StreamUtils.copyToString(input, StandardCharsets.UTF_8);
        }
        String first = retry.replace(RETRY_KEY, IDEMPOTENCY_KEY).replace("  \"retryOfOperationId\": \"12345\",\n", "");
        String fingerprint = parse(KEY, first).getFingerprint();
        assertEquals("ff40ae972a4c686456fa3c09af6eb79950188a9c126897dfe887edc1ea0afa29", fingerprint);
        assertEquals(fingerprint, parse(KEY, retry).getFingerprint());
        when(service.submit(eq(RETRY_KEY), argThat(payload -> fingerprint.equals(payload.getFingerprint())), eq(12345L)))
                .thenReturn(new DataOperationView.Receipt("12346", DataOperation.Status.RUNNING));
        mvc.perform(submit(retry)).andExpect(status().isOk())
                .andExpect(jsonPath("$.returnCode").value("SUC0000"))
                .andExpect(jsonPath("$.errorMsg").value(""))
                .andExpect(jsonPath("$.body").value(aMapWithSize(3)))
                .andExpect(jsonPath("$.body.operationId").isString())
                .andExpect(jsonPath("$.body.operationId").value("12346"))
                .andExpect(jsonPath("$.body.status").value("RUNNING"))
                .andExpect(jsonPath("$.body.purpose").value("AUTHORIZATION_SEED_V1"));
    }

    @Test
    void rejectsInvalidRetryReferenceWithoutCallingService() throws Exception {
        String original = json(KEY);
        for (String field : new String[]{"\"retryOfOperationId\":12345", "\"retryOfOperationId\":null",
                "\"retryOfOperationId\":\"-1\"", "\"retryOfOperationId\":\"0\"", "\"retryOfOperationId\":\"\"",
                "\"retryOfOperationId\":\"+12\"", "\"retryOfOperationId\":\" 12\"",
                "\"retryOfOperationId\":\"9223372036854775808\"",
                "\"retryOfOperationId\":\"1\",\"retryOfOperationId\":\"2\"",
                "\"retryOfOperationId\":\"1\",\"force\":true", "\"force\":true"}) {
            mvc.perform(submit("{" + field + "," + original.substring(1)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.returnCode").value("INVALID_ARGUMENT"));
        }
        verifyNoInteractions(service);
    }

    /** 未鉴权请求、未知或重复属性、伪造表名、类型转换和请求超限均在执行前拒绝。 */
    @Test
    void rejectsInvalidRequestsWithoutCallingService() throws Exception {
        mvc.perform(post(BASE + "/batch").servletPath(BASE + "/batch").contentType(MediaType.APPLICATION_JSON)
                .content(json(KEY))).andExpect(status().isUnauthorized());
        String original = json(KEY);
        for (String invalid : new String[]{
                original.replace("\"enable\":1", "\"enable\":\"1\""),
                original.replace("\"enable\":1", "\"enable\":1.0"),
                original.replace("\"enable\":1", "\"enable\":-1"),
                original.replace("\"enable\":1", "\"enable\":0,\"enable\":1"),
                original.replace("\"enable\":1", "\"enable\":1,\"sql\":\"SELECT 1\""),
                original.replace(KEY + "_role", "another." + KEY + "_role"),
                original.replace("\"page:home\"", "\"operation:home\""),
                original.replace("\"resource_key\":\"system_authorization_management\"", "\"resource_key\":\"home\""),
                original.replace("\"member_id\":\"" + SUBJECT + "\"", "\"member_id\":\"\""),
                original + "{}"}) {
            mvc.perform(submit(invalid)).andExpect(status().isBadRequest())
                    .andExpect(content().string(not(containsString(SUBJECT))));
        }
        String oversized = original + new String(new char[AuthorizationSeedPayload.MAX_BYTES]).replace('\0', ' ');
        mvc.perform(submit(oversized)).andExpect(status().isPayloadTooLarge());
        verifyNoInteractions(service);
    }

    /** 管理员数越界和重复自然键在受理前拒绝。 */
    @Test
    void rejectsRowLimitsAndDuplicateKeys() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode original = (ObjectNode) mapper.readTree(json(KEY));
        ArrayNode members = (ArrayNode) original.path("tables").get(3).path("rows");
        ObjectNode member = (ObjectNode) members.get(0);
        members.add(member.deepCopy());
        mvc.perform(submit(original.toString())).andExpect(status().isBadRequest());
        members.remove(1);
        for (int index = 0; index < AuthorizationSeedPayload.MAX_ADMINS; index++) {
            members.add(member.deepCopy().put("member_id", "fixture-" + index));
        }
        mvc.perform(submit(original.toString())).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    /** 明确区分同键冲突与未查到原操作。 */
    @Test
    void returnsConflictAndMissingLookup() throws Exception {
        when(service.submit(eq(IDEMPOTENCY_KEY), any(), isNull()))
                .thenThrow(new BizException(ResourceErrorCode.IDEMPOTENCY_CONFLICT));
        when(service.byKey(KEY, IDEMPOTENCY_KEY)).thenThrow(new BizException(ResourceErrorCode.RESOURCE_NOT_FOUND));
        mvc.perform(submit(json(KEY))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.returnCode").value("IDEMPOTENCY_CONFLICT"));
        String lookup = BASE + "/by-key/" + IDEMPOTENCY_KEY;
        mvc.perform(authorize(get(lookup), lookup)).andExpect(status().isNotFound());
    }

    /** 指纹对属性顺序稳定，对业务内容变化敏感。 */
    @Test
    void fingerprintsCanonicalContent() {
        String original = json(KEY);
        String reordered = original.replace("\"enable\":1,\"is_system\":1", "\"is_system\":1,\"enable\":1");
        String changed = original.replace(SUBJECT, "different-subject");
        org.junit.jupiter.api.Assertions.assertEquals(parse(KEY, original).getFingerprint(), parse(KEY, reordered).getFingerprint());
        org.junit.jupiter.api.Assertions.assertNotEquals(parse(KEY, original).getFingerprint(), parse(KEY, changed).getFingerprint());
    }

    /** 提交 UTF-8 请求体。 */
    private static MockHttpServletRequestBuilder submit(String json) {
        return authorize(post(BASE + "/batch"), BASE + "/batch").contentType(MediaType.APPLICATION_JSON).content(json);
    }

    /** 使 MockMvc 请求经过真实路径鉴权。 */
    private static MockHttpServletRequestBuilder authorize(MockHttpServletRequestBuilder request, String path) {
        return request.servletPath(path).header("Authorization", "Bearer fixture-key");
    }
}
