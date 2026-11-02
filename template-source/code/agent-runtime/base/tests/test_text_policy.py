"""当前纯文本策略的离线回归，不使用真实模型或数据库。"""
from dataclasses import FrozenInstanceError
from dataclasses import replace
from types import SimpleNamespace
import httpx
import pytest

from app.agent.context import RuntimeContext
from app.agent.execution_policy import load_execution_policies
from app.agent.execution_guard import TextOnlyGuard
from app.agent.factory import create_agent
from app.interaction.schemas import RuntimeRequestError, parse_run_request
from app.models.factory import create_chat_model
from app.settings import load_settings
from app.server.execution import ExecutionRegistry, RunExecution, current_execution, apply_request_budget


def test_actual_default_graph_has_no_tool_node():
    """对真实 DeepAgents 装配检查工具节点，不能只检查静态说明或 tools 参数。"""
    policy = load_execution_policies(("chat",))["chat"]
    context = RuntimeContext(namespace="probe:text", user_id="trusted", authentication_mode="anonymous")
    graph = create_agent(agent_id="chat", model=create_chat_model(load_settings()), runtime_context=context,
                         checkpointer=None, execution_policy=policy)
    assert "model" in graph.nodes and "tools" not in graph.nodes
    with pytest.raises(FrozenInstanceError):
        policy.agent_id = "forged"


def test_client_system_role_and_state_are_rejected():
    """官方类型合法也不能注入系统角色、工具或内部状态。"""
    base = {"threadId": "thread", "runId": "run", "state": {}, "tools": [], "context": [],
            "forwardedProps": {}, "messages": [{"id": "one", "role": "user", "content": "开始"}]}
    for changed in ({"state": {"approved": True}}, {"messages": [{"id": "one", "role": "system", "content": "越权"}]}):
        with pytest.raises(RuntimeRequestError):
            parse_run_request({**base, **changed})


def test_model_tool_intent_is_rejected_before_dispatch():
    """模型输出的工具意图在回到图路由前失败。"""
    guard = TextOnlyGuard(load_execution_policies(("chat",))["chat"])
    with pytest.raises(RuntimeRequestError, match="工具能力"):
        guard._response(SimpleNamespace(result=[SimpleNamespace(tool_calls=[{"name": "write_file"}])]))


def test_declared_business_tool_is_allowed_without_default_tools():
    """文本输出保留正式业务工具，仍拒绝未声明工具扩权。"""
    policy = replace(load_execution_policies(("chat",))["chat"], tool_ids=("lookup",), model_requests=8)
    guard = TextOnlyGuard(policy)
    guard._request(SimpleNamespace(tools=[{"name": "lookup"}], response_format=None))
    response = SimpleNamespace(result=[SimpleNamespace(tool_calls=[{"name": "lookup"}])])
    assert guard._response(response) is response
    with pytest.raises(RuntimeRequestError):
        guard._request(SimpleNamespace(tools=[{"name": "write_file"}], response_format=None))


def test_skill_reads_only_declared_file_and_rejects_changed_content(tmp_path, monkeypatch):
    """技能读取只开放正式文档；启用资源不等于开放任意文件读取或静默沿用旧内容。"""
    from hashlib import sha256
    from types import MappingProxyType
    import app.agent.execution_guard as guard_module
    path = tmp_path / "SKILL.md"
    path.write_text("正式技能内容", encoding="utf-8")
    relative = "agent-runtime/src/app/skills/business/lookup/SKILL.md"
    policy = replace(load_execution_policies(("chat",))["chat"], resource_files=(relative,), model_requests=8,
        resource_sha256=MappingProxyType({relative: sha256(path.read_bytes()).hexdigest()}))
    monkeypatch.setattr(guard_module, "_resource_path", lambda relative: path)
    reader = guard_module.resource_tools(policy)[0]
    assert "正式技能内容" in reader.invoke({"file_path": "/business/lookup/SKILL.md"})
    with pytest.raises(RuntimeRequestError):
        reader.invoke({"file_path": "/etc/passwd"})
    path.write_text("人工改动", encoding="utf-8")
    with pytest.raises(RuntimeError, match="已变化"):
        reader.invoke({"file_path": "/business/lookup/SKILL.md"})


def test_probe_budget_blocks_second_actual_provider_send(monkeypatch):
    """供应商第一次发送前消费预算，SDK 重试也不能发出第二次请求。"""
    import time
    monkeypatch.setenv("AGENT_RUNTIME_ACCEPTANCE_REQUEST_LIMIT", "1")
    context = RuntimeContext(namespace="probe:text", user_id="trusted", authentication_mode="anonymous")
    run = RunExecution(ExecutionRegistry(), "chat", context, "thread", False, time.monotonic())
    token = current_execution.set(run)
    try:
        apply_request_budget(httpx.Request("POST", "https://example.invalid/model"))
        with pytest.raises(RuntimeRequestError, match="预算已消费"):
            apply_request_budget(httpx.Request("POST", "https://example.invalid/model"))
    finally:
        current_execution.reset(token)
