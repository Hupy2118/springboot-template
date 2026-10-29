from __future__ import annotations

import re
from collections.abc import Callable
from typing import Any

from deepagents import create_deep_agent

from app.agent.context import RuntimeContext
from app.interaction.schemas import RuntimeRequestError


_AGENT_ID = re.compile(r"^[a-z][a-z0-9_]{0,127}$")
AgentBuilder = Callable[..., Any]


def _validate_agent_id_format(agent_id: str) -> None:
    """拒绝不能安全映射到 app.agent 子模块的 Agent ID。"""

    if not _AGENT_ID.fullmatch(agent_id):
        raise RuntimeRequestError("invalid_agent_id", "Agent ID 无效。")


def _create_chat_agent(
    *,
    model: Any,
    runtime_context: RuntimeContext,
    checkpointer: Any,
) -> Any:
    """创建模板内置的最小可运行 Chat Agent。"""

    del runtime_context
    return create_deep_agent(
        model=model,
        context_schema=RuntimeContext,
        tools=[],
        system_prompt="你是一个可靠的 AI 助手，请使用清晰、准确的语言回答用户。",
        checkpointer=checkpointer,
        name="chat",
    )


_AGENT_BUILDERS: dict[str, AgentBuilder] = {
    "chat": _create_chat_agent,
}


def validate_agent_id(agent_id: str) -> None:
    """校验请求是否指向当前模板实际提供的 Agent。"""

    _validate_agent_id_format(agent_id)
    if agent_id not in _AGENT_BUILDERS:
        raise RuntimeRequestError(
            "agent_not_found",
            "指定业务 Agent 尚未生成。",
        )


def create_agent(
    *,
    agent_id: str,
    model: Any,
    runtime_context: RuntimeContext,
    checkpointer: Any,
) -> Any:
    """通过当前应用注册的 Builder 创建 Deep Agent。"""

    validate_agent_id(agent_id)
    return _AGENT_BUILDERS[agent_id](
        model=model,
        runtime_context=runtime_context,
        checkpointer=checkpointer,
    )


def available_agent_ids() -> tuple[str, ...]:
    """返回已装配的 Agent 注册身份，供就绪检查复核。"""
    return tuple(sorted(_AGENT_BUILDERS))
