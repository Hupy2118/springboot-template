from __future__ import annotations

import hashlib
from dataclasses import dataclass
from typing import Any, AsyncIterator, Literal

from langgraph.types import Command

from app.agent.context import RuntimeContext
from app.interaction.schemas import (
    PendingInteraction,
    RuntimeRequestError,
    RuntimeRunRequest,
)
from app.persistence.checkpointer import checkpointer_config


@dataclass(frozen=True)
class TextDelta:
    """保存一个可发送给 AG-UI 客户端的文本增量。"""

    text: str


@dataclass(frozen=True)
class InteractionOutcome:
    """保存一次 Agent 运行的完成或等待用户状态。"""

    status: Literal["completed", "awaiting_user"]
    pending_interaction: PendingInteraction | None = None


InteractionEvent = TextDelta | InteractionOutcome


def _thread_key(agent_id: str, context: RuntimeContext, thread_id: str) -> str:
    """生成不暴露用户标识且跨进程稳定的 checkpoint 线程键。"""

    identity = "\x1f".join(
        (
            agent_id,
            context.namespace,
            context.authentication_mode,
            context.enterprise_id or "",
            context.user_id,
            thread_id,
        )
    )
    return hashlib.sha256(identity.encode("utf-8")).hexdigest()


def _message_text(content: Any) -> str:
    """从 LangChain 文本或内容块中提取可见文本，忽略思考块。"""

    if isinstance(content, str):
        return content
    if not isinstance(content, list):
        return ""
    parts: list[str] = []
    for item in content:
        if not isinstance(item, dict):
            continue
        if item.get("type") in {"text", "output_text"} and isinstance(item.get("text"), str):
            parts.append(item["text"])
    return "".join(parts)


def _pending_interaction(snapshot: Any) -> PendingInteraction | None:
    """从 LangGraph 状态快照中读取并严格校验唯一待交互项。"""

    tasks = getattr(snapshot, "tasks", ()) if snapshot is not None else ()
    values: list[Any] = []
    for task in tasks or ():
        for interrupt in getattr(task, "interrupts", ()) or ():
            values.append(getattr(interrupt, "value", None))
    if not values:
        return None
    if len(values) != 1:
        raise RuntimeRequestError("multiple_pending_interactions", "当前运行存在多个待交互项。")
    try:
        return PendingInteraction.model_validate(values[0])
    except ValueError as exc:
        raise RuntimeRequestError(
            "invalid_pending_interaction",
            "业务 Agent 返回了无效的待交互状态。",
        ) from exc


async def _agent_state(agent: Any, config: dict[str, Any]) -> Any:
    """读取 Agent checkpoint 状态；不支持状态读取时返回空值。"""

    reader = getattr(agent, "aget_state", None)
    if not callable(reader):
        return None
    return await reader(config)


def _agent_input(
    *,
    request: RuntimeRunRequest,
    pending: PendingInteraction | None,
) -> dict[str, Any] | Command:
    """根据当前 checkpoint 决定开始消息运行或恢复待交互命令。"""

    response = request.interaction_response
    if pending is not None:
        if response is None:
            raise RuntimeRequestError(
                "interaction_response_required",
                "当前会话正在等待用户回复。",
                recoverable=True,
            )
        if response.interaction_id != pending.interaction_id:
            raise RuntimeRequestError(
                "interaction_conflict",
                "待交互状态已经变化，请刷新后重试。",
                recoverable=True,
            )
        return Command(resume=response.value)
    if response is not None:
        raise RuntimeRequestError(
            "stale_interaction_response",
            "当前会话没有可恢复的待交互项。",
            recoverable=True,
        )
    if not request.messages:
        raise RuntimeRequestError("message_required", "AG-UI Chat 至少需要一条消息。")
    return {"messages": request.messages}


async def stream_agent_interaction(
    *,
    agent: Any,
    agent_id: str,
    request: RuntimeRunRequest,
    runtime_context: RuntimeContext,
) -> AsyncIterator[InteractionEvent]:
    """流式运行 Agent，并在结尾返回完成或待交互结果。"""

    config = checkpointer_config(
        _thread_key(agent_id, runtime_context, request.thread_id)
    )
    config["metadata"] = {
        "agent_id": agent_id,
        "thread_id": request.thread_id,
        "run_id": request.run_id,
        "authentication_mode": runtime_context.authentication_mode,
        "user_sha256": hashlib.sha256(
            runtime_context.user_id.encode("utf-8")
        ).hexdigest(),
    }
    before = _pending_interaction(await _agent_state(agent, config))
    agent_input = _agent_input(request=request, pending=before)
    async for item in agent.astream(agent_input, config=config, context=runtime_context, stream_mode="messages"):
        message = item[0] if isinstance(item, tuple) and item else item
        text = _message_text(getattr(message, "content", ""))
        if text:
            yield TextDelta(text=text)
    after = _pending_interaction(await _agent_state(agent, config))
    yield InteractionOutcome(
        status="awaiting_user" if after is not None else "completed",
        pending_interaction=after,
    )
