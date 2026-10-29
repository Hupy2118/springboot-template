"""业务 Service 共享的可信调用上下文。"""

from __future__ import annotations

from dataclasses import dataclass

from app.agent.context import RuntimeContext
from app.infrastructure.database import BusinessDatabase


@dataclass(frozen=True)
class ApplicationServiceContext:
    """把已验证身份和独立业务数据库传给业务 Service 与 Agent Tool。"""

    principal: RuntimeContext
    database: BusinessDatabase
