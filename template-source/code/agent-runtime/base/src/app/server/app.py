from __future__ import annotations

from contextlib import asynccontextmanager
import time
from typing import Any, AsyncIterator

from fastapi import Cookie, FastAPI, Header, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse, JSONResponse

from app.api.router import business_router
from app.agent.context import RuntimeContext, RuntimeContextError
from app.infrastructure.database import BusinessDatabase
from app.interaction.schemas import RuntimeRequestError, parse_run_request
from app.models.factory import close_chat_models
from app.persistence.checkpointer import close_checkpointers
from app.security.adapters import (
    PublicAuthenticationAdapter,
    create_public_authentication_adapter,
    validate_authentication_settings,
)
from app.security.service import resolve_public_principal
from app.server.agui import build_agent_runtime_stream
from app.server.health import router as health_router
from app.server.deployment import DeploymentLock
from app.server.execution import ExecutionRegistry, RunExecution, RuntimeStreamingResponse
from app.server.request_limits import read_run_payload
from app.settings import RuntimeSettings, load_settings


@asynccontextmanager
async def lifespan(application: FastAPI) -> AsyncIterator[None]:
    """先取得命名空间执行权；实际请求退出后再关闭资源并释放进程锁。"""
    deployment = application.state.deployment
    deployment.acquire()
    try:
        from app.agent.factory import available_agent_ids, create_agent
        from app.agent.execution_policy import load_execution_policies
        from app.models.factory import create_chat_model
        from app.persistence.checkpointer import get_checkpointer
        settings = application.state.runtime_settings
        policies = load_execution_policies(available_agent_ids())
        application.state.execution_policies = policies
        checkpointer = await get_checkpointer(settings)
        # 只构造并核验实际图，不发送模型请求或执行业务节点。
        context = RuntimeContext(namespace=settings.namespace, user_id="startup-inspection", authentication_mode="anonymous")
        for agent_id, policy in policies.items():
            create_agent(agent_id=agent_id, model=create_chat_model(settings), runtime_context=context,
                         checkpointer=checkpointer, execution_policy=policy)
        yield
    finally:
        if application.state.executions.active:
            # 不能在实际任务仍存活时释放命名空间锁；由受控启动器回收整个进程。
            raise RuntimeError("Runtime 仍有未收口执行，拒绝提前关闭资源或释放进程锁。")
        try:
            await close_checkpointers()
            await close_chat_models()
        finally:
            deployment.close()


def create_app(
    settings: RuntimeSettings | None = None,
    authentication_adapter: PublicAuthenticationAdapter | None = None,
) -> FastAPI:
    """创建一事通/匿名 Principal 与 AG-UI Direct Edge。"""

    runtime_settings = settings or load_settings()
    validate_authentication_settings(runtime_settings)
    public_authentication_adapter = authentication_adapter
    if runtime_settings.auth_enabled and public_authentication_adapter is None:
        public_authentication_adapter = create_public_authentication_adapter(
            runtime_settings
        )
    application = FastAPI(title="XCodeAgent Agent Runtime", lifespan=lifespan)
    application.state.runtime_settings = runtime_settings
    application.state.deployment = DeploymentLock(runtime_settings)
    application.state.executions = ExecutionRegistry()
    application.state.public_authentication_adapter = public_authentication_adapter
    application.state.business_database = BusinessDatabase(
        runtime_settings.database
    )
    application.add_middleware(
        CORSMiddleware,
        allow_origins=list(runtime_settings.allowed_origins),
        allow_origin_regex=None,
        allow_credentials=True,
        allow_methods=["GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"],
        allow_headers=["Authorization", "Content-Type", "traceparent"],
    )
    application.include_router(health_router)
    application.include_router(business_router)

    @application.exception_handler(RuntimeRequestError)
    async def business_agent_error(_request: Request, error: RuntimeRequestError) -> JSONResponse:
        """普通接口显式交付失败或待交互状态，不把待审批包装为检测成功。"""
        pending = getattr(error, "pending", None)
        from app.server.errors import business_error_status
        status = business_error_status(error)
        return JSONResponse(status_code=status, content={"status": "awaiting_user" if pending else "failed",
            "error": {"code": error.code, "message": str(error), "recoverable": error.recoverable},
            **(pending or {})})

    def stream_response(
        *,
        agent_id: str,
        payload: dict[str, Any],
        runtime_context: RuntimeContext,
        accept: str,
        runtime_settings: RuntimeSettings,
        received_at: float,
        anonymous_token: str | None = None,
    ) -> StreamingResponse:
        """构造 Direct AG-UI 流，并在需要时签发匿名会话 Cookie。"""

        try:
            request = parse_run_request(payload)
        except RuntimeRequestError as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc
        execution = RunExecution(application.state.executions, agent_id, runtime_context,
                                 request.thread_id, request.inspect_interaction, received_at)
        response = RuntimeStreamingResponse(
            build_agent_runtime_stream(
                agent_id=agent_id,
                request=request,
                runtime_context=runtime_context,
                settings=runtime_settings,
                accept=accept,
                execution=execution,
                execution_policy=application.state.execution_policies.get(agent_id),
            ),
            execution=execution,
            media_type="text/event-stream",
            headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
        )
        if anonymous_token is not None:
            response.set_cookie(
                key=runtime_settings.anonymous_cookie_name,
                value=anonymous_token,
                max_age=runtime_settings.anonymous_session_ttl_seconds,
                httponly=True,
                secure=runtime_settings.cookie_secure,
                samesite="lax",
                path="/",
            )
        return response

    @application.post("/agents/{agent_id}/run")
    async def run_agent(
        request: Request,
        agent_id: str,
        accept: str = Header(default="text/event-stream"),
        authorization: str = Header(default=""),
        anonymous_session: str = Cookie(
            default="", alias=runtime_settings.anonymous_cookie_name
        ),
        traceparent: str = Header(default=""),
    ) -> StreamingResponse:
        """建立公开 Principal 并返回业务 Agent AG-UI SSE。"""
        policy = application.state.execution_policies.get(agent_id)
        if policy is None or (not policy.public_endpoint and runtime_settings.runtime_profile != "debug"):
            raise HTTPException(status_code=404, detail="当前 Agent 没有公开运行入口。")
        received_at = time.monotonic()
        # 路由不使用 Body(dict)，确保字节上限在 JSON 解码之前生效。
        payload = await read_run_payload(request, received_at)
        try:
            resolution = await resolve_public_principal(
                settings=runtime_settings,
                request=request,
                authentication_adapter=public_authentication_adapter,
                authorization=authorization,
                anonymous_token=anonymous_session,
                traceparent=traceparent,
            )
        except RuntimeContextError as exc:
            raise HTTPException(status_code=401, detail=str(exc)) from exc
        return stream_response(
            agent_id=agent_id,
            payload=payload,
            runtime_context=resolution.context,
            accept=accept,
            runtime_settings=runtime_settings,
            received_at=received_at,
            anonymous_token=resolution.anonymous_token,
        )

    return application


app = create_app()
