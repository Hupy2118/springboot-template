"""页面与业务服务共享的 Agent 执行入口，不经本机 HTTP 重入。"""

import asyncio
from collections.abc import AsyncIterator
from contextlib import aclosing
from copy import deepcopy
from dataclasses import replace
import json
import time
from typing import Any
from uuid import uuid4

from app.agent.contracts import validate_value
from app.agent.context import RuntimeContext
from app.settings import RuntimeSettings
from app.agent.factory import create_agent, validate_agent_id
from app.interaction.inspection import inspect_interaction
from app.interaction.schemas import RuntimeRequestError, RuntimeRunRequest
from app.interaction.service import InteractionOutcome, TextDelta, stream_agent_interaction
from app.models.factory import create_chat_model
from app.persistence.checkpointer import get_checkpointer
from app.server.execution import RunExecution, TEXT_BYTES, current_execution


async def stream_agent(*, agent_id: str, request: RuntimeRunRequest, runtime_context: RuntimeContext,
                       settings: RuntimeSettings, execution: RunExecution, execution_policy) -> AsyncIterator:
    """执行已确认能力，统一输入校验、模型装配、文本预算和最终业务结果。"""
    if execution.admission_error is not None:
        raise execution.admission_error
    execution.remaining()
    if execution_policy is None or execution_policy.agent_id != agent_id:
        raise RuntimeRequestError("agent_policy_missing", "当前 Agent 未交付执行策略。")
    contract = execution_policy.contract
    if not request.inspect_interaction and request.interaction_response is None:
        validate_value(contract["inputSchema"], request.input if request.input is not None else {})
    if request.interaction_response is not None and not execution_policy.interaction_kinds:
        raise RuntimeRequestError("undeclared_interaction", "当前 Agent 未声明交互续接能力。")
    validate_agent_id(agent_id)
    persistent = contract["mode"] == "conversation" or bool(contract["interactionKinds"])
    checkpointer = await get_checkpointer(settings) if persistent else None
    if request.inspect_interaction:
        yield (await inspect_interaction(checkpointer, agent_id, runtime_context, request.thread_id)
               if persistent else InteractionOutcome(status="completed"))
        return
    if not request.messages and request.interaction_response is None:
        if contract["mode"] != "task" and request.input is None:
            raise RuntimeRequestError("message_required", "会话模式需要普通用户消息。")
        request = replace(request, messages=[{"id": request.run_id, "role": "user", "content": "执行本次业务检测任务。"}])
    elif contract["mode"] == "task" and not persistent and request.messages:
        request = replace(request, messages=[request.messages[-1]])
    model = create_chat_model(settings)
    agent = create_agent(agent_id=agent_id, model=model, runtime_context=runtime_context,
                         checkpointer=checkpointer, execution_policy=execution_policy)
    text_bytes = 0
    async with aclosing(stream_agent_interaction(agent=agent, agent_id=agent_id, request=request,
            runtime_context=runtime_context, execution_contract=contract)) as events:
        async for event in events:
            execution.remaining()
            if isinstance(event, TextDelta):
                text_bytes += len(event.text.encode())
                if text_bytes > TEXT_BYTES:
                    raise RuntimeRequestError("output_limit", "Agent 输出超过上限，结果可能未知。")
                if contract["textOutput"]:
                    yield event
            else:
                if event.pending_interaction is not None and event.pending_interaction.kind not in contract["interactionKinds"]:
                    raise RuntimeRequestError("undeclared_interaction", "Agent 返回了未声明交互。")
                yield event


def _tokens(pointer: str) -> list[str]:
    """解析合同中的 JSON Pointer，空字符串代表整个业务对象。"""
    if not isinstance(pointer, str) or (pointer and not pointer.startswith("/")):
        raise RuntimeRequestError("invalid_agent_mapping", "Agent 字段映射不是合法 JSON Pointer。")
    return [part.replace("~1", "/").replace("~0", "~") for part in pointer[1:].split("/")] if pointer else []


def map_values(value: Any, bindings: dict[str, str]) -> Any:
    """按已确认映射复制字段，数组可整体映射；缺失来源明确失败，不猜默认值。"""
    result = {}
    for target, source in bindings.items():
        selected = value
        try:
            for part in _tokens(source):
                selected = selected[int(part)] if isinstance(selected, list) else selected[part]
        except (KeyError, IndexError, TypeError, ValueError) as exc:
            raise RuntimeRequestError("agent_mapping_source_missing", "Agent 调用映射缺少来源字段。") from exc
        parts = _tokens(target)
        if not parts:
            if len(bindings) != 1:
                raise RuntimeRequestError("invalid_agent_mapping", "根对象映射不能与字段映射混用。")
            return deepcopy(selected)
        current = result
        for part in parts[:-1]:
            current = current.setdefault(part, {})
        current[parts[-1]] = deepcopy(selected)
    return result


class AgentInvoker:
    """业务服务持有由平台装配的可信执行依赖，不接受客户端模型或身份配置。"""

    def __init__(self, *, settings, registry, policies, principal):
        """保存当前应用资源和已验证 Principal，Agent 实例在每次执行中独立创建。"""
        self.settings, self.registry, self.policies, self.principal = settings, registry, policies, principal

    def _prepare(self, binding, payload, response_schema, thread_id, interaction_response, run_id=None):
        """从服务端合同构造任务请求和结果转换器，客户端只提供业务值。"""
        from app.interaction.schemas import InteractionResponse
        agent_id = binding["agentId"]
        policy = self.policies.get(agent_id)
        if policy is None:
            raise RuntimeRequestError("agent_not_found", "目标 Agent 尚未交付。")
        values = map_values(payload, binding["inputBindings"]) if interaction_response is None else None
        request = RuntimeRunRequest(thread_id=thread_id or str(uuid4()), run_id=run_id or str(uuid4()), messages=[],
            interaction_response=InteractionResponse.model_validate(interaction_response) if interaction_response is not None else None,
            input=values)

        def transform(result):
            """校验映射后的完整业务响应，不能用错误字段或常量冒充检测成功。"""
            mapped = map_values(result, binding["resultBindings"])
            validate_value(response_schema, mapped, result=True)
            return mapped

        return agent_id, policy, request, transform

    async def invoke_agent(self, *, binding: dict, payload: dict, response_schema: dict,
                           thread_id: str | None = None, interaction_response: dict | None = None) -> Any:
        """一次性返回业务结果；等待交互明确返回错误与续接身份，不自动批准。"""
        if binding["responseMode"] != "json":
            raise RuntimeRequestError("response_mode_mismatch", "当前接口未声明一次性 JSON 返回。")
        agent_id, policy, request, transform = self._prepare(binding, payload, response_schema, thread_id, interaction_response)
        execution = RunExecution(self.registry, agent_id, self.principal, request.thread_id, False, time.monotonic())
        execution.start()

        async def collect():
            """绑定同一供应商预算并收集唯一终态，不重放已发生的工具副作用。"""
            token = current_execution.set(execution)
            try:
                outcome = None
                async with aclosing(stream_agent(agent_id=agent_id, request=request, runtime_context=self.principal,
                        settings=self.settings, execution=execution, execution_policy=policy)) as events:
                    async for event in events:
                        if isinstance(event, InteractionOutcome):
                            outcome = event
                if outcome is None:
                    raise RuntimeRequestError("agent_result_missing", "Agent 未返回终态，结果未知。")
                if outcome.status == "awaiting_user":
                    error = RuntimeRequestError("interaction_required", "本次执行需要用户交互，尚未完成。", recoverable=True)
                    error.pending = {"agentId": agent_id, "threadId": request.thread_id,
                                     "pendingInteraction": outcome.pending_interaction.model_dump(mode="json", by_alias=True)}
                    raise error
                return transform(outcome.result)
            except RuntimeRequestError:
                raise
            except Exception as exc:
                from app.server.errors import public_error
                raise public_error(exc, self.settings) from exc
            finally:
                current_execution.reset(token)

        task = asyncio.create_task(collect())
        try:
            done, _ = await asyncio.wait({task}, timeout=max(0, execution.deadline - time.monotonic()))
            if not done:
                raise RuntimeRequestError("run_timeout", "Agent 执行超时，业务结果可能未知。")
            return await task
        finally:
            cleanup = asyncio.create_task(execution.settle(task))
            self.registry.cleanups.add(cleanup)
            cleanup.add_done_callback(self.registry.cleanups.discard)
            await asyncio.shield(cleanup)

    def stream_agent(self, *, binding: dict, payload: dict, response_schema: dict,
                     thread_id: str | None = None, run_id: str | None = None, interaction_response: dict | None = None):
        """以标准 AG-UI 适配相同执行入口，终态携带同一份映射后的业务结果。"""
        from app.server.agui import build_agent_runtime_stream
        from app.server.execution import RuntimeStreamingResponse
        if binding["responseMode"] != "ag-ui":
            raise RuntimeRequestError("response_mode_mismatch", "当前接口未声明 AG-UI 流式返回。")
        agent_id, policy, request, transform = self._prepare(binding, payload, response_schema, thread_id, interaction_response, run_id)
        execution = RunExecution(self.registry, agent_id, self.principal, request.thread_id, False, time.monotonic())
        return RuntimeStreamingResponse(build_agent_runtime_stream(agent_id=agent_id, request=request,
            runtime_context=self.principal, settings=self.settings, accept="text/event-stream", execution=execution,
            execution_policy=policy, result_transform=transform), execution=execution, media_type="text/event-stream",
            headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})
