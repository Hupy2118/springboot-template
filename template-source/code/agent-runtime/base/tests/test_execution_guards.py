"""执行互斥、期限和真实任务清理的定向回归，不访问供应商或 MySQL。"""
import asyncio
from dataclasses import replace
import time
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock

import httpx
import pytest

from app.agent.context import RuntimeContext
from app.interaction.schemas import InteractionResponse, RuntimeRequestError, RuntimeRunRequest
from app.interaction.service import stream_agent_interaction
from app.server.execution import (ExecutionRegistry, RunExecution, RuntimeStreamingResponse,
                                  apply_request_budget, current_execution)


def execution(registry, thread="thread", user="trusted", inspect=False):
    """为独立线程构造由服务端身份派生的真实执行预算。"""
    context = RuntimeContext(namespace="probe:execution", user_id=user, authentication_mode="anonymous")
    return RunExecution(registry, "chat", context, thread, inspect, time.monotonic())


class PendingAgent:
    """用可观察的图调用替代模型，检查非法恢复不会执行图。"""

    def __init__(self, kind):
        """记录唯一待交互项和收到的原生恢复命令。"""
        self.kind, self.calls, self.command = kind, 0, None

    async def aget_state(self, config):
        """返回当前线程的中断快照。"""
        pending = {"schemaVersion": 1, "interactionId": "pending", "kind": self.kind, "message": "请回复", "payload": {}}
        return SimpleNamespace(tasks=[SimpleNamespace(interrupts=[SimpleNamespace(value=pending)])] if not self.calls else [])

    async def astream(self, command, **kwargs):
        """记录真实图入口接收值，不调用模型或执行写入。"""
        self.calls += 1
        self.command = command
        yield SimpleNamespace(content="完成")


@pytest.mark.asyncio
@pytest.mark.parametrize("kind,value,valid", [
    ("clarification", "问题说明", True), ("clarification", " ", False), ("clarification", True, False),
    ("clarification", "中" * 5462, False), ("confirmation", True, True), ("confirmation", False, True),
    ("confirmation", "false", False), ("confirmation", 0, False), ("approval_required", 1, False),
    ("approval_required", False, True), ("approval_required", {}, False),
])
async def test_resume_values_are_strict_before_graph_entry(kind, value, valid):
    """拒绝隐式真值转换，合法拒绝必须保持原生 False。"""
    agent = PendingAgent(kind)
    request = RuntimeRunRequest("thread", "run", [], InteractionResponse(interactionId="pending", value=value))
    context = RuntimeContext(namespace="probe:execution", user_id="trusted", authentication_mode="anonymous")
    if not valid:
        with pytest.raises(RuntimeRequestError) as caught:
            _ = [item async for item in stream_agent_interaction(agent=agent, agent_id="chat", request=request, runtime_context=context)]
        assert caught.value.code == "invalid_interaction_value"
        assert agent.calls == 0
    else:
        events = [item async for item in stream_agent_interaction(agent=agent, agent_id="chat", request=request, runtime_context=context)]
        assert events[-1].status == "completed"
        assert agent.command.resume == value and type(agent.command.resume) is type(value)


def test_thread_inspection_and_trusted_identity_capacity_do_not_queue():
    """相同线程的 run/resume/query 均互斥，换 thread 无法绕过身份与进程容量。"""
    registry = ExecutionRegistry()
    first = execution(registry)
    first.start()
    for inspect in (False, True):
        duplicate = execution(registry, inspect=inspect)
        duplicate.start()
        assert duplicate.admission_error.code == "thread_busy"
    second = execution(registry, "thread2")
    second.start()
    excess = execution(registry, "thread3")
    excess.start()
    assert excess.admission_error.code == "execution_limit"
    others = [execution(registry, "other" + str(i), user="other") for i in range(2)]
    for item in others:
        item.start()
    excess = execution(registry, "third", user="third")
    excess.start()
    assert excess.admission_error.code == "execution_limit"
    inspection = execution(registry, "idle", inspect=True)
    inspection.start()
    assert inspection.admission_error is None and registry.running == 4
    for item in [first, second, *others, inspection]:
        registry.release(item)
    assert registry.active == {} and registry.identities == {} and registry.running == 0


@pytest.mark.asyncio
async def test_cancellation_keeps_thread_busy_until_task_really_exits(monkeypatch):
    """模拟不配合取消的任务，清理耗尽不能提前释放并发，故障实例不得接单。"""
    import app.server.execution as module
    monkeypatch.setattr(module, "CLEANUP_SECONDS", 0.01)
    registry, started, finish = ExecutionRegistry(), asyncio.Event(), asyncio.Event()
    run = execution(registry)
    run.start()

    async def stubborn():
        """取消后仍等待驱动结束，模拟真实在途工作尚未退出。"""
        started.set()
        try:
            await finish.wait()
        except asyncio.CancelledError:
            await finish.wait()

    task = asyncio.create_task(stubborn())
    await started.wait()
    await run.settle(task)
    assert not registry.healthy and registry.running == 1 and not task.done()
    rejected = execution(registry, "new")
    rejected.start()
    assert rejected.admission_error.code == "runtime_unhealthy"
    finish.set()
    await task
    await asyncio.sleep(0)
    assert registry.active == {} and registry.running == 0


async def run_response(content, run, send):
    """通过真实 ASGI 发送路径执行响应，不把直接消费生成器当背压测试。"""
    async def receive():
        """保持连接在线，让结束或超时由响应本身决定。"""
        await asyncio.Event().wait()
    response = RuntimeStreamingResponse(content, execution=run, media_type="text/event-stream")
    await response({"type": "http", "asgi": {"spec_version": "2.4"}}, receive, send)


@pytest.mark.asyncio
@pytest.mark.parametrize("slow_send", [False, True])
async def test_deadline_covers_graph_wait_and_http_backpressure(slow_send):
    """模型等待和客户端不读都会耗尽同一个整体期限，收口后才释放登记。"""
    registry, closed = ExecutionRegistry(), asyncio.Event()
    run = execution(registry)
    run.deadline = time.monotonic() + 0.03

    async def content():
        """分别模拟等待模型与已生成文本但发送被阻塞。"""
        try:
            if not slow_send:
                await asyncio.Event().wait()
            yield "data: text\n\n"
        finally:
            closed.set()

    async def send(message):
        """在正文发送处施加真实背压。"""
        if slow_send and message["type"] == "http.response.body":
            await asyncio.Event().wait()

    await run_response(content(), run, send)
    assert closed.is_set() and run.cancelled
    assert registry.healthy and registry.running == 0 and registry.active == {}


@pytest.mark.asyncio
async def test_disconnect_waits_for_actual_generator_cleanup():
    """客户端断开先取消图并完成其 finally，再释放线程互斥。"""
    registry, started, closed = ExecutionRegistry(), asyncio.Event(), asyncio.Event()
    run = execution(registry)

    async def content():
        """记录实际运行与取消收尾的时序。"""
        try:
            started.set()
            await asyncio.Event().wait()
            yield "unreachable"
        finally:
            assert run.thread_key in registry.active
            closed.set()

    async def send(message):
        """接收正常响应头，不施加背压。"""
        pass

    async def receive():
        """只有图已进入执行后才模拟连接中断。"""
        await started.wait()
        return {"type": "http.disconnect"}

    response = RuntimeStreamingResponse(content(), execution=run)
    await response({"type": "http"}, receive, send)
    assert closed.is_set() and registry.active == {}


def test_provider_timeout_is_capped_and_late_retry_is_rejected():
    """每次供应商请求使用剩余预算，取消后不能重新发出请求。"""
    run = execution(ExecutionRegistry())
    token = current_execution.set(run)
    try:
        request = httpx.Request("POST", "http://example.invalid", extensions={"timeout": {"read": 300, "connect": 1}})
        apply_request_budget(request)
        assert 0 < request.extensions["timeout"]["read"] <= 120
        assert request.extensions["timeout"]["connect"] == 1
        run.cancelled = True
        with pytest.raises(RuntimeRequestError, match="停止"):
            apply_request_budget(request)
    finally:
        current_execution.reset(token)


@pytest.mark.asyncio
async def test_busy_is_complete_agui_failure_before_checkpoint_or_model(monkeypatch):
    """冲突请求只交付 busy 终态，不触及 checkpoint、模型或原请求。"""
    import app.server.agui as agui
    from app.settings import load_settings
    registry = ExecutionRegistry()
    incumbent, rejected = execution(registry), execution(registry, inspect=True)
    incumbent.start()
    model, saver = Mock(), AsyncMock()
    monkeypatch.setattr(agui, "create_chat_model", model)
    monkeypatch.setattr(agui, "get_checkpointer", saver)
    context = RuntimeContext(namespace="probe:execution", user_id="trusted", authentication_mode="anonymous")
    request = RuntimeRunRequest("thread", "run", [], None, inspect_interaction=True)
    frames = []

    async def send(message):
        """收集实际发送的官方编码器帧以检查生命周期闭合。"""
        frames.append(message.get("body", b""))

    content = agui.build_agent_runtime_stream(agent_id="chat", request=request, runtime_context=context,
        settings=load_settings(), accept="text/event-stream", execution=rejected,
        execution_policy=__import__("app.agent.execution_policy", fromlist=["load_execution_policies"]).load_execution_policies(("chat",))["chat"])
    await run_response(content, rejected, send)
    text = b"".join(frames).decode()
    assert "thread_busy" in text and "RUN_STARTED" in text and "RUN_FINISHED" in text
    assert '"status":"failed"' in text
    model.assert_not_called()
    saver.assert_not_awaited()
    assert registry.active[incumbent.thread_key] is incumbent
    registry.release(incumbent)


@pytest.mark.asyncio
async def test_output_limit_closes_graph_and_never_reports_success(monkeypatch):
    """公开文本超限先关闭图，再发送有界失败，不能静默截断后报告 completed。"""
    import app.server.agui as agui
    from app.interaction.service import InteractionOutcome, TextDelta
    from app.settings import load_settings
    registry, closed = ExecutionRegistry(), asyncio.Event()
    run = execution(registry)
    monkeypatch.setattr(agui, "get_checkpointer", AsyncMock(return_value=object()))
    monkeypatch.setattr(agui, "inspect_interaction", AsyncMock(return_value=InteractionOutcome("completed")))
    monkeypatch.setattr(agui, "create_chat_model", Mock(return_value=object()))
    monkeypatch.setattr(agui, "create_agent", Mock(return_value=object()))

    async def oversized(**kwargs):
        """模拟模型实际输出超限，finally 是真实关闭图的观测点。"""
        try:
            yield TextDelta("x" * (128 * 1024 + 1))
        finally:
            closed.set()

    monkeypatch.setattr(agui, "stream_agent_interaction", oversized)
    frames = []

    async def send(message):
        """记录正文而不改变发送行为。"""
        frames.append(message.get("body", b""))

    context = RuntimeContext(namespace="probe:execution", user_id="trusted", authentication_mode="anonymous")
    request = RuntimeRunRequest("thread", "run", [{"role": "user", "content": "开始"}], None)
    content = agui.build_agent_runtime_stream(agent_id="chat", request=request, runtime_context=context,
        settings=load_settings(), accept="text/event-stream", execution=run,
        execution_policy=__import__("app.agent.execution_policy", fromlist=["load_execution_policies"]).load_execution_policies(("chat",))["chat"])
    await run_response(content, run, send)
    text = b"".join(frames).decode()
    assert closed.is_set() and "output_limit" in text and '"status":"completed"' not in text
    assert "RUN_FINISHED" in text and registry.active == {}
