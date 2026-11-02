"""直接读取当前 root checkpoint 的公开中断，不构造模型或执行图。"""
from __future__ import annotations

from types import SimpleNamespace
from langgraph.constants import INTERRUPT

from app.agent.context import RuntimeContext
from app.interaction.service import InteractionOutcome, _pending_interaction, _thread_key
from app.persistence.checkpointer import checkpointer_config


async def inspect_interaction(checkpointer, agent_id: str, context: RuntimeContext, thread_id: str) -> InteractionOutcome:
    """只取当前 checkpoint 的 pending writes，不读取历史或把消息状态返回前端。"""
    saved = await checkpointer.aget_tuple(checkpointer_config(_thread_key(agent_id, context, thread_id)))
    interrupts = []
    if saved is not None:
        for _task_id, channel, values in saved.pending_writes or ():
            if channel == INTERRUPT:
                interrupts.extend(values)
    pending = _pending_interaction(SimpleNamespace(tasks=[SimpleNamespace(interrupts=interrupts)]))
    return InteractionOutcome(status="awaiting_user" if pending is not None else "completed", pending_interaction=pending)
