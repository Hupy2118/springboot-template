"""业务 REST Endpoint 复用 Agent Runtime 的公开身份解析。"""

from __future__ import annotations

from fastapi import Header, HTTPException, Request, Response

from app.agent.context import RuntimeContextError
from app.application.context import ApplicationServiceContext
from app.security.service import resolve_public_principal


async def trusted_business_context(
    request: Request,
    response: Response,
    authorization: str = Header(default=""),
    traceparent: str = Header(default=""),
) -> ApplicationServiceContext:
    """从认证适配器或受保护匿名 Cookie 建立业务服务上下文。"""

    settings = request.app.state.runtime_settings
    try:
        resolution = await resolve_public_principal(
            settings,
            request=request,
            authentication_adapter=request.app.state.public_authentication_adapter,
            authorization=authorization,
            anonymous_token=request.cookies.get(settings.anonymous_cookie_name, ""),
            traceparent=traceparent,
        )
    except RuntimeContextError as exc:
        raise HTTPException(status_code=401, detail=str(exc)) from exc
    if resolution.anonymous_token is not None:
        response.set_cookie(
            key=settings.anonymous_cookie_name,
            value=resolution.anonymous_token,
            max_age=settings.anonymous_session_ttl_seconds,
            httponly=True,
            secure=settings.cookie_secure,
            samesite="lax",
            path="/",
        )
    return ApplicationServiceContext(
        principal=resolution.context,
        database=request.app.state.business_database,
    )
