from __future__ import annotations

from collections.abc import AsyncIterator
from contextlib import aclosing
from typing import Any
from uuid import uuid4

from ag_ui.core import (
    CustomEvent,
    RunFinishedEvent,
    RunStartedEvent,
    StateSnapshotEvent,
    TextMessageContentEvent,
    TextMessageEndEvent,
    TextMessageStartEvent,
)
from ag_ui.encoder import EventEncoder
from fastapi.encoders import jsonable_encoder

from app.agent.context import RuntimeContext
from app.agent.factory import create_agent, validate_agent_id
from app.interaction.schemas import RuntimeRequestError, RuntimeRunRequest
from app.interaction.service import (
    InteractionOutcome,
    TextDelta,
    stream_agent_interaction,
    _agent_input,
)
from app.interaction.inspection import inspect_interaction
from app.models.factory import create_chat_model
from app.persistence.checkpointer import get_checkpointer
from app.settings import RuntimeSettings
from app.server.execution import RunExecution, TEXT_BYTES
from app.agent.execution_policy import ExecutionPolicy


def _result_payload(
    *,
    request: RuntimeRunRequest,
    agent_id: str,
    outcome: InteractionOutcome,
) -> dict[str, Any]:
    """构造成功与等待用户共用的有界公开结果。"""

    pending = (
        outcome.pending_interaction.model_dump(mode="json", by_alias=True)
        if outcome.pending_interaction is not None
        else None
    )
    return {
        "schemaVersion": 1,
        "agentId": agent_id,
        "threadId": request.thread_id,
        "runId": request.run_id,
        "status": outcome.status,
        "pendingInteraction": pending,
    }


def _error_payload(
    *,
    request: RuntimeRunRequest,
    agent_id: str,
    error: RuntimeRequestError,
) -> dict[str, Any]:
    """构造不暴露异常栈、路径或密钥的业务错误结果。"""

    return {
        "schemaVersion": 1,
        "agentId": agent_id,
        "threadId": request.thread_id,
        "runId": request.run_id,
        "status": "failed",
        "error": {
            "code": error.code,
            "message": str(error),
            "recoverable": error.recoverable,
        },
    }


def build_agent_runtime_stream(
    *,
    agent_id: str,
    request: RuntimeRunRequest,
    runtime_context: RuntimeContext,
    settings: RuntimeSettings,
    accept: str,
    execution: RunExecution,
    execution_policy: ExecutionPolicy | None,
) -> AsyncIterator[str]:
    """构造具有唯一终态的业务 Agent AG-UI SSE 事件流。"""

    encoder = EventEncoder(accept or "text/event-stream")
    message_id = str(uuid4())

    async def stream() -> AsyncIterator[str]:
        """执行 Agent 并逐帧编码文本、状态、结果和终态。"""

        yield encoder.encode(
            RunStartedEvent(threadId=request.thread_id, runId=request.run_id)
        )
        yield encoder.encode(
            TextMessageStartEvent(messageId=message_id, role="assistant")
        )
        try:
            if execution.admission_error is not None:
                raise execution.admission_error
            execution.remaining()
            if execution_policy is None or execution_policy.agent_id != agent_id:
                raise RuntimeRequestError("agent_policy_missing", "当前 Agent 未交付可执行策略。")
            if request.interaction_response is not None and not execution_policy.interaction_kinds:
                raise RuntimeRequestError("undeclared_interaction", "当前 Agent 未声明交互续接能力。")
            validate_agent_id(agent_id)
            checkpointer = await get_checkpointer(settings)
            before = await inspect_interaction(checkpointer, agent_id, runtime_context, request.thread_id)
            if before.pending_interaction is not None and before.pending_interaction.kind not in execution_policy.interaction_kinds:
                raise RuntimeRequestError("undeclared_interaction", "当前 checkpoint 包含未声明交互，不能恢复。")
            outcome: InteractionOutcome | None = None
            if request.inspect_interaction:
                outcome = before
            else:
                # 先复核当前待办/续接值，再构造供应商客户端；整个区间持有同一线程锁。
                model = create_chat_model(settings)
                agent = create_agent(
                    agent_id=agent_id,
                    model=model,
                    runtime_context=runtime_context,
                    checkpointer=checkpointer,
                    execution_policy=execution_policy,
                )
                text_bytes = 0
                async with aclosing(stream_agent_interaction(
                    agent=agent,
                    agent_id=agent_id,
                    request=request,
                    runtime_context=runtime_context,
                )) as events:
                    async for event in events:
                        execution.remaining()
                        if isinstance(event, TextDelta):
                            text_bytes += len(event.text.encode("utf-8"))
                            if text_bytes > TEXT_BYTES:
                                raise RuntimeRequestError("output_limit", "本次回答超过公开文本上限，执行已停止，结果可能未知。")
                            yield encoder.encode(TextMessageContentEvent(messageId=message_id, delta=event.text))
                        else:
                            outcome = event
            if outcome is None:
                raise RuntimeError("Agent Runtime 未产生终态。")
            if outcome.pending_interaction is not None and outcome.pending_interaction.kind not in execution_policy.interaction_kinds:
                raise RuntimeRequestError("undeclared_interaction", "Agent 返回了正式合同未声明的交互。")
            payload = jsonable_encoder(
                _result_payload(request=request, agent_id=agent_id, outcome=outcome)
            )
            yield encoder.encode(TextMessageEndEvent(messageId=message_id))
            yield encoder.encode(
                CustomEvent(name="agent_runtime_result.v1", value=payload)
            )
            yield encoder.encode(
                StateSnapshotEvent(snapshot={"agentRuntime": payload})
            )
            yield encoder.encode(
                RunFinishedEvent(
                    threadId=request.thread_id,
                    runId=request.run_id,
                    result={"agentRuntime": payload},
                )
            )
        except RuntimeRequestError as exc:
            payload = jsonable_encoder(
                _error_payload(request=request, agent_id=agent_id, error=exc)
            )
            yield encoder.encode(
                TextMessageContentEvent(messageId=message_id, delta=str(exc))
            )
            yield encoder.encode(TextMessageEndEvent(messageId=message_id))
            yield encoder.encode(
                CustomEvent(name="agent_runtime_error.v1", value=payload)
            )
            yield encoder.encode(
                StateSnapshotEvent(snapshot={"agentRuntime": payload})
            )
            yield encoder.encode(
                RunFinishedEvent(
                    threadId=request.thread_id,
                    runId=request.run_id,
                    result={"agentRuntime": payload},
                )
            )
        except Exception as exc:
            # 仅暴露经过脱敏的业务失败；保留错误码、余额/权限和超时原因。
            from app.server.errors import public_error
            error = public_error(exc, settings)
            payload = jsonable_encoder(_error_payload(request=request, agent_id=agent_id, error=error))
            yield encoder.encode(TextMessageContentEvent(messageId=message_id, delta=str(error)))
            yield encoder.encode(TextMessageEndEvent(messageId=message_id))
            yield encoder.encode(CustomEvent(name="agent_runtime_error.v1", value=payload))
            yield encoder.encode(StateSnapshotEvent(snapshot={"agentRuntime": payload}))
            yield encoder.encode(RunFinishedEvent(threadId=request.thread_id, runId=request.run_id,
                                                  result={"agentRuntime": payload}))

    return stream()
