package com.cmbchina.backend.gateway.agent;

import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 提供只在 local Profile 注册的 Agent 准入状态调试接口。 */
@RestController
@Profile("local")
@RequestMapping("/_xcode/debug/agents")
@ConditionalOnProperty(prefix = "gateway.agent", name = "policy-mode", havingValue = "local")
public class LocalAgentDebugController {
    private final LocalAgentPolicyStore store;

    /** 注入可变的本地 Agent 策略状态。 */
    public LocalAgentDebugController(LocalAgentPolicyStore store) { this.store = store; }

    /** 返回当前本地允许与冻结状态。 */
    @GetMapping
    public Map<String, Set<String>> state() { return store.snapshot(); }
    /** 本地调试时允许访问指定 Agent。 */
    @PostMapping("/{agentId}/allow")
    public Map<String, Set<String>> allow(@PathVariable String agentId) { store.allow(agentId); return store.snapshot(); }
    /** 本地调试时禁止访问指定 Agent。 */
    @PostMapping("/{agentId}/deny")
    public Map<String, Set<String>> deny(@PathVariable String agentId) { store.deny(agentId); return store.snapshot(); }
    /** 本地调试时冻结指定 Agent。 */
    @PostMapping("/{agentId}/freeze")
    public Map<String, Set<String>> freeze(@PathVariable String agentId) { store.freeze(agentId); return store.snapshot(); }
    /** 本地调试时解冻指定 Agent。 */
    @PostMapping("/{agentId}/unfreeze")
    public Map<String, Set<String>> unfreeze(@PathVariable String agentId) { store.unfreeze(agentId); return store.snapshot(); }
}
