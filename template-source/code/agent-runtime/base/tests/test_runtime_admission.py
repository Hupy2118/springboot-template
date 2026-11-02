"""进程锁、HTTP 输入与只读查询的合同回归，不连接供应商或 MySQL。"""
import asyncio
from dataclasses import replace
import json
import os
from pathlib import Path
import subprocess
import sys
import time
from unittest.mock import Mock

from fastapi.testclient import TestClient
from langgraph.checkpoint.sqlite.aio import AsyncSqliteSaver
from langgraph.graph import StateGraph, MessagesState, START, END
from langgraph.types import interrupt
import pytest

from app.agent.context import RuntimeContext
from app.interaction.inspection import inspect_interaction
from app.interaction.schemas import RuntimeRequestError, RuntimeRunRequest, InteractionResponse, parse_run_request
from app.interaction.service import stream_agent_interaction
from app.server.app import create_app
from app.server.deployment import DeploymentLock, validate_workers
from app.settings import load_settings


def payload(messages=None, props=None):
    """构造官方 SDK 的当前请求形状。"""
    return {"threadId": "thread", "runId": "run", "state": {}, "tools": [], "context": [],
            "messages": messages if messages is not None else [{"id": "question", "role": "user", "content": "你好"}],
            "forwardedProps": props or {}}


@pytest.mark.parametrize("variable,value", [("AGENT_RUNTIME_WORKERS", "2"), ("AGENT_RUNTIME_WORKERS", ""),
                                          ("WEB_CONCURRENCY", "2"), ("UVICORN_WORKERS", "2")])
def test_startup_rejects_unsupported_workers(monkeypatch, variable, value):
    """显式单 worker 是准入条件，不能静默降级错误配置。"""
    monkeypatch.setenv("AGENT_RUNTIME_WORKERS", "1")
    monkeypatch.setenv(variable, value)
    with pytest.raises(RuntimeError):
        validate_workers()


def test_routes_do_not_open_without_controlled_server_configuration(tmp_path, monkeypatch):
    """无法证明实际服务拓扑时 lifespan 必须失败，不返回伪造的 ready。"""
    import app.server.deployment as deployment
    monkeypatch.setattr(deployment, "_server_config", None)
    with pytest.raises(RuntimeError, match="无法核验"):
        with TestClient(create_app(replace(load_settings(), working_dir=tmp_path))):
            pass


def test_namespace_lock_excludes_another_process_and_releases_on_exit(tmp_path):
    """由真实子进程竞争内核锁，正常释放后能重新取得同一命名空间。"""
    settings = replace(load_settings(), working_dir=tmp_path, namespace="probe:lock")
    lock = DeploymentLock(settings)
    script = """
from app.settings import load_settings
from app.server.deployment import DeploymentLock
from app.server.deployment import bind_server_config
from uvicorn import Config
bind_server_config(Config("app.server.app:app", host="127.0.0.1", workers=1, reload=False))
lock = DeploymentLock(load_settings())
try:
    lock.acquire()
except RuntimeError:
    raise SystemExit(23)
lock.close()
"""
    environment = {**os.environ, "AGENT_RUNTIME_WORKING_DIR": str(tmp_path), "AGENT_RUNTIME_NAMESPACE": settings.namespace}
    lock.acquire()
    try:
        competing = subprocess.run([sys.executable, "-c", script], env=environment, capture_output=True, timeout=10)
        assert competing.returncode == 23, competing.stderr.decode()
        assert lock.facts(True)["processLockHeld"]
    finally:
        lock.close()
    released = subprocess.run([sys.executable, "-c", script], env=environment, capture_output=True, timeout=10)
    assert released.returncode == 0, released.stderr.decode()
    assert not lock.facts(True)["processLockHeld"]


def test_http_limits_precede_json_and_official_type_parsing(tmp_path, monkeypatch):
    """即使 Content-Length 伪报，过大字节也在解析和模型构造之前拒绝。"""
    import app.server.agui as agui
    model = Mock(side_effect=AssertionError("不能构造模型"))
    monkeypatch.setattr(agui, "create_chat_model", model)
    settings = replace(load_settings(), working_dir=tmp_path)
    with TestClient(create_app(settings)) as client:
        response = client.post("/agents/chat/run", content=b"{" + b" " * (256 * 1024),
                               headers={"content-length": "1", "content-type": "application/json"})
        assert response.status_code == 413
        assert client.post("/agents/chat/run", content="invalid json").status_code == 422
    model.assert_not_called()


@pytest.mark.parametrize("messages,code", [
    ([{"id": str(i), "role": "user", "content": "x"} for i in range(129)], "message_limit"),
    ([{"id": "u", "role": "user", "content": "中" * 5462}], "user_text_limit"),
])
def test_message_budgets_reject_without_truncating(messages, code):
    """按数量与 UTF-8 字节限制新增输入，不静默裁切消息。"""
    with pytest.raises(RuntimeRequestError) as caught:
        parse_run_request(payload(messages))
    assert caught.value.code == code


def test_inspection_does_not_construct_a_model_or_consume_capacity(tmp_path, monkeypatch):
    """真实 HTTP 查询走轻量 checkpoint 路径，仍输出完整官方生命周期。"""
    import app.server.agui as agui
    model = Mock(side_effect=AssertionError("只读查询不允许构造模型"))
    monkeypatch.setattr(agui, "create_chat_model", model)
    application = create_app(replace(load_settings(), working_dir=tmp_path))
    with TestClient(application) as client:
        response = client.post("/agents/chat/run", json=payload([], {"inspectInteraction": True}))
        assert response.status_code == 200
        assert "RUN_STARTED" in response.text and "RUN_FINISHED" in response.text
        assert '"status":"completed"' in response.text
        assert application.state.executions.running == 0
        assert application.state.executions.active == {}
        application.state.executions.healthy = False
        health = client.get("/health")
        assert health.status_code == 503 and health.json()["checks"]["execution"] == "failed"
    model.assert_not_called()


@pytest.mark.asyncio
async def test_direct_checkpoint_inspection_matches_native_interrupt_before_and_after_resume(tmp_path):
    """真实 LangGraph/SQLite 证明轻量查询不会残留已消费的确认或写入 checkpoint。"""
    async def confirm(state):
        """只建立原生确认中断，不使用工具或模型。"""
        interrupt({"schemaVersion": 1, "interactionId": "confirm", "kind": "confirmation", "message": "确认？", "payload": {}})
        return {"messages": []}

    context = RuntimeContext(namespace="probe:inspect", user_id="trusted", authentication_mode="anonymous")
    async with AsyncSqliteSaver.from_conn_string(str(tmp_path / "checkpoint.sqlite")) as saver:
        graph = StateGraph(MessagesState)
        graph.add_node("confirm", confirm)
        graph.add_edge(START, "confirm")
        graph.add_edge("confirm", END)
        agent = graph.compile(checkpointer=saver)
        initial = RuntimeRunRequest("thread", "run", [{"role": "user", "content": "开始"}], None)
        events = [event async for event in stream_agent_interaction(agent=agent, agent_id="chat", request=initial, runtime_context=context)]
        found = await inspect_interaction(saver, "chat", context, "thread")
        assert found == events[-1] and found.status == "awaiting_user"
        resumed = replace(initial, run_id="resume", messages=[], interaction_response=InteractionResponse(interactionId="confirm", value=False))
        events = [event async for event in stream_agent_interaction(agent=agent, agent_id="chat", request=resumed, runtime_context=context)]
        found = await inspect_interaction(saver, "chat", context, "thread")
        assert found == events[-1] and found.status == "completed"
