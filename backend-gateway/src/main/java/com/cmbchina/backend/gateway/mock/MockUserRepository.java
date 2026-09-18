package com.cmbchina.backend.gateway.mock;

import com.cmbchina.backend.common.security.XcodePrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Repository;

/** 从只读 classpath 数据文件加载行外开发使用的固定 Mock 用户。 */
@Repository
@Profile("local")
@ConditionalOnProperty(prefix = "gateway.security", name = "mode", havingValue = "local")
public class MockUserRepository {
    private static final String DATA_PATH = "mock/users.json";
    private final XcodePrincipal defaultUser;

    /** 启动时加载并校验 Mock 用户数据，缺失或无效时拒绝启动。 */
    public MockUserRepository(ObjectMapper objectMapper) {
        try (InputStream input = new ClassPathResource(DATA_PATH).getInputStream()) {
            XcodePrincipal[] users = objectMapper.readValue(input, XcodePrincipal[].class);
            if (users.length == 0 || users[0].getUserId() == null
                    || users[0].getUserId().trim().isEmpty()) {
                throw new IllegalStateException("Mock 用户数据不能为空。");
            }
            this.defaultUser = users[0];
        } catch (Exception exception) {
            throw new IllegalStateException("无法加载 " + DATA_PATH + "。", exception);
        }
    }

    /** 返回固定用户的副本，避免运行时修改 classpath 原始数据。 */
    public XcodePrincipal defaultUser() {
        return new XcodePrincipal(defaultUser.getUserId(), defaultUser.getEmployeeId(),
                defaultUser.getSapId(), defaultUser.getEnterpriseId());
    }
}
