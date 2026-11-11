"""多 Agent 内部调用、结构化结果与返回协议的离线行为回归。"""

import asyncio
from dataclasses import replace
import json

import pytest
from langchain_core.language_models.fake_chat_models import FakeListChatModel

from app.agent.context import RuntimeContext
from app.agent.execution_policy import load_execution_policies
from app.agent.invocation import AgentInvoker, map_values
from app.interaction.schemas import RuntimeRequestError
from app.server.execution import ExecutionRegistry
from app.settings import load_settings


def task_contract() -> dict:
    """声明业务样本和固定结构的风险结果，不依赖自然语言解析约定。"""
    return {"mode": "task", "inputSchema": {"type": "object", "properties": {"sample": {"type": "string"}},
        "required": ["sample"], "additionalProperties": False}, "resultSchema": {"type": "object",
        "properties": {"risk": {"type": "string"}}, "required": ["risk"], "additionalProperties": False},
        "textOutput": False, "publicStateSchema": None, "outcomes": [], "interactionKinds": []}


def invocation(monkeypatch, response: str = '{"risk":"low"}') -> AgentInvoker:
    """装配真实 DeepAgents 注册与共享执行器，仅把供应商模型替换为本地确定性模型。"""
    import app.agent.factory as factory
    import app.agent.invocation as runtime
    from unittest.mock import AsyncMock
    policy = load_execution_policies(("chat",))["chat"]
    identities = ("api_scan", "database_scan", "page_scan")
    policies = {identity: replace(policy, agent_id=identity, api_contract_id=f"agent.{identity}",
        endpoint_id=f"agent.{identity}.run", contract_json=json.dumps(task_contract()), public_endpoint=False)
        for identity in identities}
    monkeypatch.setattr(factory, "_AGENT_BUILDERS", {identity: factory._create_chat_agent for identity in identities})
    monkeypatch.setattr(runtime, "create_chat_model", lambda _settings: FakeListChatModel(responses=[response]))
    monkeypatch.setattr(runtime, "get_checkpointer", AsyncMock(side_effect=AssertionError("单次无交互任务不得打开持久化记忆")))
    principal = RuntimeContext(namespace="probe:capabilities", user_id="trusted", authentication_mode="anonymous")
    return AgentInvoker(settings=load_settings(), registry=ExecutionRegistry(), policies=policies, principal=principal)


def binding(identity: str, mode: str = "json") -> dict:
    """共享同一参数和结果映射，仅接口返回方式变化。"""
    return {"agentId": identity, "responseMode": mode, "inputBindings": {"/sample": "/body/sample"}, "resultBindings": {"": ""}}


@pytest.mark.asyncio
async def test_three_independent_agents_reuse_the_same_internal_invoker(monkeypatch):
    """三个无页面 Agent 均可独立执行，任务结束释放登记且不建立长期会话。"""
    caller = invocation(monkeypatch)
    for identity in ("api_scan", "database_scan", "page_scan"):
        result = await caller.invoke_agent(binding=binding(identity), payload={"body": {"sample": identity}},
                                          response_schema=task_contract()["resultSchema"])
        assert result == {"risk": "low"}
    assert not caller.registry.active
    assert caller.registry.running == 0


@pytest.mark.asyncio
async def test_invalid_input_never_reaches_model_and_invalid_output_is_not_success(monkeypatch):
    """正式输入和结果校验都实际生效，错误结果不修补、不重试。"""
    caller = invocation(monkeypatch, response='{"unexpected":true}')
    with pytest.raises(RuntimeRequestError) as invalid_input:
        await caller.invoke_agent(binding=binding("api_scan"), payload={"body": {"sample": 3}},
                                  response_schema=task_contract()["resultSchema"])
    assert invalid_input.value.code == "invalid_agent_input"
    with pytest.raises(RuntimeRequestError) as invalid_output:
        await caller.invoke_agent(binding=binding("api_scan"), payload={"body": {"sample": "x"}},
                                  response_schema=task_contract()["resultSchema"])
    assert invalid_output.value.code == "invalid_agent_result"
    assert not caller.registry.active


@pytest.mark.asyncio
async def test_streaming_and_json_share_result_and_preserve_correlation(monkeypatch):
    """流式入口使用同一业务结果，并保留官方客户端签发的 thread/run 身份。"""
    caller = invocation(monkeypatch)
    response = caller.stream_agent(binding=binding("api_scan", "ag-ui"), payload={"body": {"sample": "x"}},
        response_schema=task_contract()["resultSchema"], thread_id="business-thread", run_id="business-run")
    frames = []

    async def receive():
        """模拟保持连接的客户端，执行结束时由响应生命周期取消监听。"""
        await asyncio.Event().wait()

    async def send(message):
        """仅记录真实响应正文，不改变事件编码或执行顺序。"""
        frames.append(message.get("body", b""))

    await response({"type": "http", "asgi": {"spec_version": "2.0"}}, receive, send)
    text = b"".join(frames).decode()
    assert "RUN_FINISHED" in text and '"risk":"low"' in text
    assert '"runId":"business-run"' in text and '"threadId":"business-thread"' in text
    assert not caller.registry.active


def test_same_mapping_reuses_arrays_and_rejects_missing_sources():
    """业务数组整体复用，缺失来源不能被静默替换成空数据。"""
    assert map_values({"rows": [{"risk": "high"}]}, {"/findings": "/rows"}) == {"findings": [{"risk": "high"}]}
    with pytest.raises(RuntimeRequestError):
        map_values({}, {"/findings": "/rows"})
