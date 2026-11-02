from __future__ import annotations

from pydantic import BaseModel, ConfigDict, Field
from app.interaction.page_context import PageContext


class RuntimeContextError(ValueError):
    """表示 Direct Runtime 无法建立可信 Principal。"""

    code = "invalid_runtime_context"


class RuntimeContext(BaseModel):
    """保存由统一认证适配器、匿名会话或 Debug 入口建立的可信身份。"""

    model_config = ConfigDict(extra="forbid", frozen=True)

    namespace: str = Field(min_length=1, max_length=256)
    user_id: str = Field(min_length=1, max_length=256)
    employee_id: str | None = Field(default=None, max_length=256)
    sap_id: str | None = Field(default=None, max_length=256)
    enterprise_id: str | None = Field(default=None, max_length=256)
    authentication_mode: str = Field(pattern="^(enterprise|anonymous|debug)$")
    traceparent: str = Field(default="", max_length=512)
    # 页面内容为非可信用户输入，不覆盖上述 Principal，也不作为授权事实。
    page_context: PageContext | None = None
