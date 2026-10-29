from __future__ import annotations

from contextlib import asynccontextmanager
from typing import Any, AsyncIterator

from fastapi import Body, Cookie, FastAPI, Header, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse

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
from app.settings import RuntimeSettings, load_settings


@asynccontextmanager
async def lifespan(_app: FastAPI) -> AsyncIterator[None]:
    """在服务退出时关闭 Runtime 持有的 checkpoint 连接。"""

    yield
    await close_checkpointers()
    await close_chat_models()


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

    def stream_response(
        *,
        agent_id: str,
        payload: dict[str, Any],
        runtime_context: RuntimeContext,
        accept: str,
        runtime_settings: RuntimeSettings,
        anonymous_token: str | None = None,
    ) -> StreamingResponse:
        """构造 Direct AG-UI 流，并在需要时签发匿名会话 Cookie。"""

        try:
            request = parse_run_request(payload)
        except RuntimeRequestError as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc
        response = StreamingResponse(
            build_agent_runtime_stream(
                agent_id=agent_id,
                request=request,
                runtime_context=runtime_context,
                settings=runtime_settings,
                accept=accept,
            ),
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
        payload: dict[str, Any] = Body(...),
        accept: str = Header(default="text/event-stream"),
        authorization: str = Header(default=""),
        anonymous_session: str = Cookie(
            default="", alias=runtime_settings.anonymous_cookie_name
        ),
        traceparent: str = Header(default=""),
    ) -> StreamingResponse:
        """建立公开 Principal 并返回业务 Agent AG-UI SSE。"""

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
            anonymous_token=resolution.anonymous_token,
        )

    return application


app = create_app()
