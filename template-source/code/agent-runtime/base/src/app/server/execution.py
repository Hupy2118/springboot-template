"""进程内执行准入、整体期限与实际任务收口；不保存历史线程锁。"""
from __future__ import annotations

import asyncio
from contextvars import ContextVar
import hashlib
import logging
import os
from threading import Lock
import time

import httpx
from fastapi.responses import StreamingResponse

from app.agent.context import RuntimeContext
from app.interaction.schemas import RuntimeRequestError
from app.interaction.service import _thread_key

RUN_SECONDS = 120.0
CLEANUP_SECONDS = 10.0
TEXT_BYTES = 128 * 1024
FATAL_MARKER = "AGENT_RUNTIME_CLEANUP_EXHAUSTED"
logger = logging.getLogger(__name__)
current_execution: ContextVar[RunExecution | None] = ContextVar("runtime_execution", default=None)


class ExecutionRegistry:
    """只登记正在执行或清理的线程，容量按服务端可信身份计数。"""

    def __init__(self) -> None:
        """初始化当前实例独占的登记表和故障闸门。"""
        self.active: dict[str, RunExecution] = {}
        self.identities: dict[str, int] = {}
        self.running = 0
        self.healthy = True
        self.cleanups: set[asyncio.Task] = set()

    def acquire(self, execution: RunExecution) -> None:
        """在同一事件循环内无等待地完成线程互斥与身份/进程容量登记。"""
        if not self.healthy:
            raise RuntimeRequestError("runtime_unhealthy", "Runtime 清理未完成，请停止当前实例。")
        if execution.thread_key in self.active:
            raise RuntimeRequestError("thread_busy", "当前会话正在执行或清理，请稍后查询。", recoverable=True)
        if not execution.inspect and (self.running >= 4 or self.identities.get(execution.identity_key, 0) >= 2):
            raise RuntimeRequestError("execution_limit", "Runtime 活跃执行已达上限，本次请求未执行。", recoverable=True)
        self.active[execution.thread_key] = execution
        if not execution.inspect:
            self.running += 1
            self.identities[execution.identity_key] = self.identities.get(execution.identity_key, 0) + 1

    def release(self, execution: RunExecution) -> None:
        """只在真实任务退出后删除本次登记；只读查询不占模型容量。"""
        if self.active.get(execution.thread_key) is not execution:
            return
        del self.active[execution.thread_key]
        if not execution.inspect:
            self.running -= 1
            remaining = self.identities[execution.identity_key] - 1
            if remaining:
                self.identities[execution.identity_key] = remaining
            else:
                del self.identities[execution.identity_key]


class RunExecution:
    """让执行层、HTTP 发送层和模型传输共享同一次执行的期限。"""

    def __init__(self, registry: ExecutionRegistry, agent_id: str, context: RuntimeContext,
                 thread_id: str, inspect: bool, received_at: float) -> None:
        """从可信 Principal 派生容量键，拒绝用请求自报身份核算预算。"""
        self.registry = registry
        self.thread_key = _thread_key(agent_id, context, thread_id)
        identity = (context.namespace, context.authentication_mode, context.enterprise_id, context.user_id)
        self.identity_key = hashlib.sha256(repr(identity).encode()).hexdigest()
        self.inspect = inspect
        self.deadline = received_at + RUN_SECONDS
        self.admission_error: RuntimeRequestError | None = None
        self.cancelled = False
        self.provider_requests = 0
        self.provider_guard = Lock()
        # 每次会话最多八次实际模型发送；验收可使用更小的确认预算，SDK 重试也计数。
        configured_limit = os.getenv("AGENT_RUNTIME_ACCEPTANCE_REQUEST_LIMIT", "8")
        self.provider_limit = int(configured_limit) if configured_limit in {"1", "8"} else 8

    def start(self) -> None:
        """记录明确的业务拒绝，由原 AG-UI 生命周期交付失败。"""
        try:
            self.registry.acquire(self)
        except RuntimeRequestError as exc:
            self.admission_error = exc

    def remaining(self) -> float:
        """在供应商每次发送前复核期限和取消，阻断迟到重试。"""
        remaining = self.deadline - time.monotonic()
        if self.cancelled or remaining <= 0:
            raise RuntimeRequestError("run_timeout", "本次执行已停止或超过整体期限，结果可能未知。")
        return remaining

    async def settle(self, task: asyncio.Task) -> None:
        """取消一次并等待真实退出；超时保留占用并请求受控启动器回收。"""
        if not task.done():
            self.cancelled = True
            task.cancel()
            done, _ = await asyncio.wait({task}, timeout=CLEANUP_SECONDS)
            if not done:
                self.registry.healthy = False
                # 固定标记没有请求或身份内容；启动器据此停止进程，绝不自动重启。
                logger.critical(FATAL_MARKER)
                task.add_done_callback(self._settled)
                return
        self._settled(task)

    def _settled(self, task: asyncio.Task) -> None:
        """取走真实任务异常并释放登记，避免遗留无限增长的锁或未读取异常。"""
        if not task.cancelled():
            task.exception()
        self.registry.release(self)


def apply_request_budget(request: httpx.Request) -> None:
    """把每次模型 HTTP 发送（含供应商重试）的超时限制在剩余总期限内。"""
    execution = current_execution.get()
    if execution is None:
        return
    remaining = execution.remaining()
    # 实际 HTTP 发送前消费预算，SDK 重试或图内再次调用也无法重复发送。
    with execution.provider_guard:
        if execution.provider_limit is not None and execution.provider_requests >= execution.provider_limit:
            raise RuntimeRequestError("supplier_budget_exhausted", "本次验收的供应商请求预算已消费，不自动重发。")
        execution.provider_requests += 1
    timeout = request.extensions.get("timeout", {})
    request.extensions["timeout"] = {
        key: min(value, remaining) if isinstance(value, (int, float)) else remaining
        for key, value in ((key, timeout.get(key)) for key in ("connect", "read", "write", "pool"))
    }


async def apply_async_request_budget(request: httpx.Request) -> None:
    """为异步供应商客户端应用同一服务端执行预算。"""
    apply_request_budget(request)


class RuntimeStreamingResponse(StreamingResponse):
    """把图运行、终态发送和客户端背压一起纳入 deadline 与清理边界。"""

    def __init__(self, content, *, execution: RunExecution, **kwargs) -> None:
        """保持官方编码器事件流，仅补充服务端资源生命周期。"""
        super().__init__(content, **kwargs)
        self.execution = execution

    async def _serve(self, scope, receive, send) -> None:
        """绑定供应商预算并确保断流时关闭仍暂停在 yield 的生成器。"""
        token = current_execution.set(self.execution)
        try:
            # 使用 asyncio 单次取消，避免 AnyIO 的重复取消打断数据库/图自身的收尾。
            await self.stream_response(send)
        finally:
            try:
                await self.body_iterator.aclose()
            finally:
                current_execution.reset(token)

    async def __call__(self, scope, receive, send) -> None:
        """独立观察实际任务，不因 wait_for 或连接取消而提前释放容量。"""
        self.execution.start()
        task = asyncio.create_task(self._serve(scope, receive, send))
        disconnected = asyncio.create_task(self.listen_for_disconnect(receive))
        try:
            done, _ = await asyncio.wait({task, disconnected},
                                        timeout=max(0, self.execution.deadline - time.monotonic()),
                                        return_when=asyncio.FIRST_COMPLETED)
            if task in done:
                await task
            # 总期限耗尽时关闭连接；不能在超期后伪造已成功交付的终态。
        finally:
            disconnected.cancel()
            # 先登记独立收口任务；连接再次取消也不能跳过清理或丢失其强引用。
            cleanup = asyncio.create_task(self.execution.settle(task))
            self.execution.registry.cleanups.add(cleanup)
            cleanup.add_done_callback(self.execution.registry.cleanups.discard)
            try:
                await asyncio.shield(cleanup)
            finally:
                await asyncio.gather(disconnected, return_exceptions=True)
