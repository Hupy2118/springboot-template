from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Literal

from ag_ui.core import RunAgentInput
from pydantic import BaseModel, ConfigDict, Field


class RuntimeRequestError(ValueError):
    """表示可以安全返回给 Java 网关的 Runtime 请求错误。"""

    def __init__(self, code: str, message: str, *, recoverable: bool = False) -> None:
        """保存稳定错误码、安全消息和可恢复标记。"""

        super().__init__(message)
        self.code = code
        self.recoverable = recoverable


class InteractionResponse(BaseModel):
    """定义用户对当前 checkpoint 待交互项的严格恢复输入。"""

    model_config = ConfigDict(populate_by_name=True, extra="forbid", frozen=True)

    interaction_id: str = Field(alias="interactionId", min_length=1, max_length=256)
    value: Any


class PendingInteraction(BaseModel):
    """定义 Agent interrupt 可公开投射的最小待交互状态。"""

    model_config = ConfigDict(populate_by_name=True, extra="forbid", frozen=True)

    schema_version: Literal[1] = Field(alias="schemaVersion")
    interaction_id: str = Field(alias="interactionId", min_length=1, max_length=256)
    kind: Literal["clarification", "confirmation", "approval_required"]
    message: str = Field(min_length=1, max_length=4096)
    payload: dict[str, Any] = Field(default_factory=dict)


@dataclass(frozen=True)
class RuntimeRunRequest:
    """保存经过官方 AG-UI 类型校验后的单次 Chat 输入。"""

    thread_id: str
    run_id: str
    messages: list[dict[str, Any]]
    interaction_response: InteractionResponse | None


def parse_run_request(payload: dict[str, Any]) -> RuntimeRunRequest:
    """使用 RunAgentInput 校验请求并提取模板需要的最小字段。"""

    try:
        run_input = RunAgentInput.model_validate(payload)
    except ValueError as exc:
        raise RuntimeRequestError("invalid_ag_ui_input", "AG-UI 请求结构无效。") from exc
    normalized = run_input.model_dump(mode="json", by_alias=True)
    forwarded_props = normalized.get("forwardedProps")
    forwarded_props = forwarded_props if isinstance(forwarded_props, dict) else {}
    raw_response = forwarded_props.get("interactionResponse")
    try:
        interaction_response = (
            InteractionResponse.model_validate(raw_response)
            if raw_response is not None
            else None
        )
    except ValueError as exc:
        raise RuntimeRequestError(
            "invalid_interaction_response",
            "待交互恢复输入无效。",
            recoverable=True,
        ) from exc
    messages = normalized.get("messages")
    return RuntimeRunRequest(
        thread_id=str(normalized.get("threadId") or ""),
        run_id=str(normalized.get("runId") or ""),
        messages=[item for item in messages if isinstance(item, dict)]
        if isinstance(messages, list)
        else [],
        interaction_response=interaction_response,
    )

