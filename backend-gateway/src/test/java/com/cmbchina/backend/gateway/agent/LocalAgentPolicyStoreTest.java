package com.cmbchina.backend.gateway.agent;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证本地 Agent 准入状态可以用于允许、冻结和解冻调试。 */
class LocalAgentPolicyStoreTest {
    /** 本地策略必须拒绝冻结 Agent，并在解冻后恢复已允许状态。 */
    @Test
    void togglesLocalAgentAdmissionState() {
        AgentGatewayProperties properties = new AgentGatewayProperties();
        properties.setAllowedAgentIds(Arrays.asList("demo-agent"));
        LocalAgentPolicyStore store = new LocalAgentPolicyStore(properties);

        assertTrue(store.evaluate("demo-agent").isAllowed());
        store.freeze("demo-agent");
        assertTrue(store.evaluate("demo-agent").isFrozen());
        assertFalse(store.evaluate("demo-agent").isAllowed());
        store.unfreeze("demo-agent");
        assertTrue(store.evaluate("demo-agent").isAllowed());
    }
}
