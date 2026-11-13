package com.cmbchina.managedb;

import com.cmbchina.managedb.adapter.web.request.AuthorizationSeedRequest;
import com.cmbchina.managedb.domain.AuthorizationSeedPayload;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/** 公共测试数据均为虚构的权限资源和管理员。 */
public final class AuthorizationSeedFixture {
    public static final String KEY = "12ab34cd56ef";
    public static final String IDEMPOTENCY_KEY = String.join("", Collections.nCopies(64, "a"));
    public static final String SUBJECT = "fixture-subject";

    /** 工具类不创建实例。 */
    private AuthorizationSeedFixture() {}

    /** 构造包含两个资源点的完整请求。 */
    public static String json(String datasourceKey) {
        return "{\"purpose\":\"AUTHORIZATION_SEED_V1\",\"idempotencyKey\":\"" + IDEMPOTENCY_KEY + "\",\"tables\":["
                + "{\"tableName\":\"" + datasourceKey + "_role\",\"rows\":[{\"role_id\":\"SYSTEM_ADMIN\","
                + "\"role_name\":\"管理员\",\"description\":\"系统角色\",\"enable\":1,\"is_system\":1,\"is_deleted\":0,\"created_by\":\"SYSTEM\"}]},"
                + "{\"tableName\":\"" + datasourceKey + "_resource\",\"rows\":[{\"key\":\"system_authorization_management\","
                + "\"name\":\"权限管理\",\"description\":\"系统资源\",\"origin\":\"system\",\"type\":\"system\","
                + "\"target_resource_ref\":\"system:authorization_management\"},{\"key\":\"home\",\"name\":\"首页\","
                + "\"description\":\"含 SQL 字符: '); DROP TABLE test; --\",\"origin\":\"business\",\"type\":\"page\","
                + "\"target_resource_ref\":\"page:home\"}]},"
                + "{\"tableName\":\"" + datasourceKey + "_role_resource\",\"rows\":[{\"role_id\":\"SYSTEM_ADMIN\","
                + "\"resource_key\":\"system_authorization_management\",\"created_by\":\"SYSTEM\"}]},"
                + "{\"tableName\":\"" + datasourceKey + "_role_member\",\"rows\":[{\"role_id\":\"SYSTEM_ADMIN\","
                + "\"member_id\":\"" + SUBJECT + "\",\"member_name\":\"Fixture Admin\",\"created_by\":\"SYSTEM\"}]}]}";
    }

    /** 通过生产解析入口构造载荷。 */
    public static AuthorizationSeedPayload parse(String key, String json) {
        return AuthorizationSeedRequest.parse(key,
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), new ObjectMapper()).getPayload();
    }
}
