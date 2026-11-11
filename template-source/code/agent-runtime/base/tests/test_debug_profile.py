"""验证独立调试身份及生产交互恢复，不连接真实模型或 MySQL。"""

from dataclasses import replace
import time
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient
from starlette.requests import Request

from app.agent.context import RuntimeContext, RuntimeContextError
from app.interaction.schemas import InteractionResponse, RuntimeRunRequest, RuntimeRequestError
from app.interaction.service import stream_agent_interaction
from app.security.debug import resolve_debug_principal
from app.security.service import resolve_public_principal
from app.server.app import create_app
from app.settings import load_settings


def debug_settings():
    """创建只用于测试的短期凭据和独立 namespace。"""
    return replace(load_settings(), runtime_profile="debug", launch_id="debug-test", namespace="debug:debug-test",
                   debug_token="test-debug-capability-" + "x" * 32, debug_expires_at=int(time.time()) + 120)


@pytest.mark.asyncio
async def test_debug_requires_loopback_current_credential_and_never_uses_cookie():
    """真实身份解析必须拒绝无凭据、过期、错误 namespace 和非 loopback 请求。"""
    settings = debug_settings()
    request = Request({"type": "http", "headers": [], "client": ("127.0.0.1", 1234)})
    resolution = await resolve_public_principal(settings, request=request, authentication_adapter=None,
                                               authorization="Bearer " + settings.debug_token, anonymous_token="fabricated-cookie")
    assert resolution.context.authentication_mode == "debug"
    assert resolution.context.namespace == "debug:debug-test"
    assert resolution.context.user_id == "debug:debug-test"
    assert resolution.anonymous_token is None
    for invalid in [replace(settings, debug_expires_at=1), replace(settings, namespace="preview:any"),
                    replace(settings, runtime_profile="preview"), replace(settings, debug_token="a-different-token")]:
        with pytest.raises(RuntimeContextError):
            resolve_debug_principal(invalid, request, "Bearer " + settings.debug_token)
    with pytest.raises(RuntimeContextError):
        resolve_debug_principal(settings, request, "")
    remote = Request({"type": "http", "headers": [], "client": ("192.0.2.1", 1234)})
    with pytest.raises(RuntimeContextError):
        resolve_debug_principal(settings, remote, "Bearer " + settings.debug_token)


def test_debug_health_returns_actual_identity_without_credentials():
    """健康检查不需要发送业务消息，公开响应没有调试秘密或 Principal。"""
    settings = debug_settings()
    with TestClient(create_app(settings)) as client:
        response = client.get("/health")
    assert response.status_code == 200
    assert response.json()["agentIds"] == ["chat"]
    assert response.json()["profile"] == "debug"
    assert response.json()["checks"]["anonymousSession"] == "not_required"
    assert response.json()["checks"]["debugSession"] == "ready"
    assert settings.debug_token not in response.text
    assert "debug:debug-test" not in response.text


def test_preview_cannot_load_debug_environment(monkeypatch):
    """当前普通预览明确拒绝收到调试凭据，而非忽略后继续启动。"""
    monkeypatch.setenv("AGENT_RUNTIME_PROFILE", "preview")
    monkeypatch.setenv("AGENT_RUNTIME_DEBUG_TOKEN", "x" * 48)
    load_settings.cache_clear()
    try:
        with pytest.raises(RuntimeError, match="普通预览"):
            load_settings()
    finally:
        load_settings.cache_clear()


@pytest.mark.asyncio
async def test_native_confirmation_is_bound_to_principal_and_does_not_write_before_approval(tmp_path):
    """使用真实 LangGraph/SQLite 和生产交互服务证明暂停、错误身份拒绝及确认后单次委托。"""
    from langgraph.checkpoint.sqlite.aio import AsyncSqliteSaver
    from langgraph.graph import StateGraph, MessagesState, START, END
    from langgraph.runtime import Runtime
    from langgraph.types import interrupt

    writes = []
    async def write_node(state: MessagesState, runtime: Runtime[RuntimeContext]):
        """用可观测内存记录替代 MySQL，确认之前不能发生业务写入。"""
        approved = interrupt({"schemaVersion": 1, "interactionId": uuid4().hex, "kind": "confirmation",
                              "message": "确认提交？", "payload": {"toolId": "submit", "arguments": {"request": "leave"}}})
        if approved is not True:
            raise ValueError("未确认")
        writes.append((runtime.context.user_id, "leave"))
        return {"messages": []}

    async def run(graph, request, context):
        """完整消费一次生产交互，不直接操纵 checkpoint 或伪造恢复命令。"""
        return [event async for event in stream_agent_interaction(agent=graph, agent_id="leave", request=request, runtime_context=context, execution_contract=TEST_EXECUTION_CONTRACT)]

    async with AsyncSqliteSaver.from_conn_string(str(tmp_path / "debug.sqlite")) as saver:
        builder = StateGraph(MessagesState, context_schema=RuntimeContext)
        builder.add_node("write", write_node)
        builder.add_edge(START, "write")
        builder.add_edge("write", END)
        graph = builder.compile(checkpointer=saver)
        context = RuntimeContext(namespace="debug:one", user_id="debug:one", authentication_mode="debug")
        initial = RuntimeRunRequest(thread_id="thread-one", run_id="r1", messages=[{"role": "user", "content": "提交请假"}], interaction_response=None)
        outcome = (await run(graph, initial, context))[-1]
        assert outcome.status == "awaiting_user"
        assert writes == []
        response = InteractionResponse(interactionId=outcome.pending_interaction.interaction_id, value=True)
        resumed = replace(initial, run_id="r2", messages=[], interaction_response=response)
        other = context.model_copy(update={"namespace": "preview:one", "user_id": "anonymous-one", "authentication_mode": "anonymous"})
        with pytest.raises(RuntimeRequestError, match="没有可恢复"):
            await run(graph, resumed, other)
        assert writes == []
        completed = (await run(graph, resumed, context))[-1]
        assert completed.status == "completed"
        assert writes == [("debug:one", "leave")]
        with pytest.raises(RuntimeRequestError, match="没有可恢复"):
            await run(graph, resumed, context)
        assert len(writes) == 1


# 当前文本会话的显式测试合同；交互测试保留确认/澄清能力。
TEST_EXECUTION_CONTRACT = {"mode": "conversation", "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False}, "resultSchema": None, "textOutput": True, "interactionKinds": ["clarification", "confirmation", "approval_required"]}
