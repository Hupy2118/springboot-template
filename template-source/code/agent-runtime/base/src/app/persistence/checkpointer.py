from __future__ import annotations

import asyncio
from contextlib import AsyncExitStack
from pathlib import Path
from typing import Any

from langgraph.checkpoint.sqlite.aio import AsyncSqliteSaver

from app.settings import RuntimeSettings


_EXIT_STACK = AsyncExitStack()
_SAVERS: dict[Path, AsyncSqliteSaver] = {}
_LOCK = asyncio.Lock()


async def get_checkpointer(settings: RuntimeSettings) -> AsyncSqliteSaver:
    """返回当前 Runtime 工作目录独占的 SQLite checkpointer。"""

    path = settings.checkpoint_db_path
    async with _LOCK:
        existing = _SAVERS.get(path)
        if existing is not None:
            return existing
        path.parent.mkdir(parents=True, exist_ok=True)
        saver = await _EXIT_STACK.enter_async_context(
            AsyncSqliteSaver.from_conn_string(str(path))
        )
        await saver.setup()
        _SAVERS[path] = saver
        return saver


async def close_checkpointers() -> None:
    """关闭所有 SQLite 连接并清理当前进程缓存。"""

    async with _LOCK:
        await _EXIT_STACK.aclose()
        _SAVERS.clear()


def checkpointer_config(thread_key: str) -> dict[str, Any]:
    """为 LangGraph 构造只含隔离线程键的运行配置。"""

    return {"configurable": {"thread_id": thread_key}}

