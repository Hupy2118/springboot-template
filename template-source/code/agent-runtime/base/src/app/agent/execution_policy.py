"""模板拥有的当前执行策略加载器；平台只更新下方明确区域，无默认执行能力。"""
from __future__ import annotations
from dataclasses import dataclass
from hashlib import sha256
import json
import os
from pathlib import Path
from types import MappingProxyType

# BEGIN PLATFORM EXECUTION POLICIES
POLICIES_JSON = '{}'
# END PLATFORM EXECUTION POLICIES


@dataclass(frozen=True)
class ExecutionPolicy:
    """保存启动时冻结的非敏感公开能力与预算，不读取客户端能力声明。"""
    agent_id: str
    api_contract_id: str
    endpoint_id: str
    capability_sha256: str
    implementation_sha256: str
    budgets: object
    tool_ids: tuple[str, ...]
    resource_files: tuple[str, ...]
    resource_sha256: object
    interaction_kinds: tuple[str, ...]
    model_requests: int
    contract_json: str
    public_endpoint: bool

    @property
    def contract(self) -> dict:
        """每次返回独立合同副本，不能修改启动时冻结的执行能力。"""
        return json.loads(self.contract_json)


def load_execution_policies(registered_ids: tuple[str, ...]) -> object:
    """必须匹配 Studio 本轮受控摘要和实际注册集合，缺失/过期即启动失败。"""
    expected = os.getenv("AGENT_RUNTIME_EXECUTION_POLICY_SHA256", "")
    if len(expected) != 64 or sha256(Path(__file__).read_bytes()).hexdigest() != expected:
        raise RuntimeError("Runtime 执行策略摘要缺失或与当前交付不一致。")
    raw = json.loads(POLICIES_JSON)
    if not isinstance(raw, dict) or set(raw) != set(registered_ids) or not raw:
        raise RuntimeError("Runtime 策略集合与实际注册 Agent 不一致。")
    result = {}
    budgets = {"requestBytes": 262144, "messages": 128, "userTextBytes": 16384, "textBytes": 131072,
               "threadRuns": 1, "identityRuns": 2, "processRuns": 4, "runSeconds": 120, "cleanupSeconds": 10}
    from app.agent.contracts import validate_contract
    for agent_id, entry in raw.items():
        interaction = entry.get("interactionContract") if isinstance(entry, dict) else None
        kinds = interaction.get("interactionKinds") if isinstance(interaction, dict) else None
        internal = entry.get("executionCapabilities") if isinstance(entry, dict) else None
        if (not isinstance(entry, dict) or entry.get("agentId") != agent_id
                or entry.get("rule") != "execution.policy.v2" or entry.get("budgets") != budgets
                or not isinstance(kinds, list) or len(kinds) != len(set(kinds))
                or set(kinds) - {"clarification", "confirmation", "approval_required"}
                or not isinstance(internal, dict) or set(internal) != {"toolIds", "resourceFiles", "modelRequests"}
                or entry.get("apiContractId") != f"agent.{agent_id}"
                or entry.get("endpointId") != f"agent.{agent_id}.run"):
            raise RuntimeError("Runtime 执行策略不满足当前会话能力声明。")
        contract = validate_contract(interaction)
        exposure = entry.get("invocation", {}).get("exposure")
        if exposure not in {"internal", "public"}:
            raise RuntimeError("Agent 入口暴露范围未声明。")
        tools, files = internal["toolIds"], internal["resourceFiles"]
        if (not isinstance(tools, list) or any(not isinstance(tool, str) for tool in tools) or len(set(tools)) != len(tools)
                or not isinstance(files, dict) or set(files) != {"skills", "knowledge"}
                or any(not isinstance(value, list) for value in files.values())):
            raise RuntimeError("Runtime 内部工具或资源能力无效。")
        resources = tuple(path for paths in files.values() for path in paths)
        for module, paths in files.items():
            for path in paths:
                if (not isinstance(path, str) or not path.startswith(f"agent-runtime/src/app/{module}/")
                        or not path.endswith(".md") or any(part.startswith(".") for part in Path(path).parts)):
                    raise RuntimeError("Runtime 正式资源路径无效。")
        digests = entry.get("resourceSha256")
        if (not isinstance(digests, dict) or set(digests) != set(resources)
                or internal["modelRequests"] != (8 if tools or files["skills"] else 1)):
            raise RuntimeError("Runtime 资源摘要或模型循环预算不一致。")
        result[agent_id] = ExecutionPolicy(agent_id, entry["apiContractId"], entry["endpointId"],
            entry["capabilitySha256"], entry["implementationSha256"], MappingProxyType(budgets.copy()),
            tuple(tools), resources, MappingProxyType(dict(digests)), tuple(kinds), internal["modelRequests"],
            json.dumps(contract, sort_keys=True), exposure == "public")
    return MappingProxyType(result)
