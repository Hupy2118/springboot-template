from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Literal

from ag_ui.core import RunAgentInput
from app.interaction.page_context import PageContext
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
    page_context: PageContext | None = None
    inspect_interaction: bool = False
    input: dict[str, Any] | None = None


def parse_run_request(payload: dict[str, Any]) -> RuntimeRunRequest:
    """使用 RunAgentInput 校验请求并提取模板需要的最小字段。"""

    allowed = {"threadId", "runId", "messages", "state", "tools", "context", "forwardedProps"}
    if set(payload) - allowed or payload.get("state", {}) != {} or payload.get("tools", []) != [] or payload.get("context", []) != []:
        raise RuntimeRequestError("undeclared_input", "当前会话不接受客户端 state、工具、额外 context 或扩权字段。")
    raw_props = payload.get("forwardedProps", {})
    if not isinstance(raw_props, dict) or set(raw_props) - {"pageId", "actionId", "contextItems", "inspectInteraction", "interactionResponse", "input"}:
        raise RuntimeRequestError("undeclared_input", "当前会话包含未声明的 forwardedProps。")
    raw_messages = payload.get("messages", [])
    if not isinstance(raw_messages, list) or any(not isinstance(item, dict) or set(item) != {"id", "role", "content"}
            or item.get("role") not in {"user", "assistant"} or not isinstance(item.get("content"), str) for item in raw_messages):
        raise RuntimeRequestError("invalid_public_messages", "当前只接受普通用户/助手文本，不接受系统、工具或嵌套调用消息。")
    try:
        run_input = RunAgentInput.model_validate(payload)
    except ValueError as exc:
        raise RuntimeRequestError("invalid_ag_ui_input", "AG-UI 请求结构无效。") from exc
    normalized = run_input.model_dump(mode="json", by_alias=True)
    forwarded_props = normalized.get("forwardedProps")
    forwarded_props = forwarded_props if isinstance(forwarded_props, dict) else {}
    inspect = forwarded_props.get("inspectInteraction", False)
    if type(inspect) is not bool or (inspect and forwarded_props.get("interactionResponse") is not None):
        raise RuntimeRequestError("invalid_state_query", "只读状态查询不能携带恢复操作。")
    context_keys = ("pageId", "actionId", "contextItems")
    try:
        page_context = (PageContext.model_validate({key: forwarded_props[key] for key in context_keys if key in forwarded_props})
                        if any(key in forwarded_props for key in context_keys) else None)
    except ValueError as exc:
        raise RuntimeRequestError("invalid_page_context", "页面上下文无效。") from exc
    raw_response = forwarded_props.get("interactionResponse")
    business_input = forwarded_props.get("input")
    if business_input is not None and not isinstance(business_input, dict):
        raise RuntimeRequestError("invalid_agent_input", "Agent 业务输入必须是对象。")
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
    if isinstance(messages, list):
        if len(messages) > 128:
            raise RuntimeRequestError("message_limit", "每次 AG-UI 请求最多 128 条消息。")
        newest_user = next((item for item in reversed(messages) if item.get("role") == "user"), None)
        if newest_user is not None:
            content = newest_user.get("content", "")
            # 先约束当前 SDK 的文本/文本块输入；历史可信投影在正式合同阶段统一切换。
            text = content if isinstance(content, str) else "".join(
                block.get("text", "") for block in content if isinstance(block, dict) and isinstance(block.get("text"), str)
            ) if isinstance(content, list) else ""
            if len(text.encode("utf-8")) > 16 * 1024:
                raise RuntimeRequestError("user_text_limit", "新增用户文本不能超过 16 KiB。")
    return RuntimeRunRequest(
        thread_id=str(normalized.get("threadId") or ""),
        run_id=str(normalized.get("runId") or ""),
        messages=[item for item in messages if isinstance(item, dict)]
        if isinstance(messages, list)
        else [],
        interaction_response=interaction_response,
        page_context=page_context,
        inspect_interaction=inspect,
        input=business_input,
    )

