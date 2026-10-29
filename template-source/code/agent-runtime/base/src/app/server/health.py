"""真实工程就绪检查；不调用模型，也不写业务数据。"""
from __future__ import annotations
import logging
from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse
from fastapi.routing import APIRoute
from app.agent.factory import available_agent_ids
from app.api.router import business_router
from app.security.service import resolve_public_principal

router = APIRouter()
logger = logging.getLogger(__name__)


@router.get("/health")
async def health(request: Request):
    """核对本轮身份、实际数据库、已注册业务/AG-UI 路由及匿名会话。"""
    settings = request.app.state.runtime_settings
    checks = {"database": "failed", "businessApi": "failed", "agUi": "failed", "anonymousSession": "failed"}
    try:
        checks["database"] = await request.app.state.business_database.probe()
        routes = [route for route in request.app.routes if isinstance(route, APIRoute)]
        mounted = {(route.path, tuple(sorted(route.methods))) for route in routes}
        expected = {(route.path, tuple(sorted(route.methods))) for route in business_router.routes if isinstance(route, APIRoute)}
        # 空业务骨架可以启动；已有业务路由必须全部真实挂载，导入错误已由 router 阻断。
        if expected <= mounted:
            checks["businessApi"] = "ready"
        if available_agent_ids() and any(route.path == "/agents/{agent_id}/run" and "POST" in route.methods for route in routes):
            checks["agUi"] = "ready"
        resolution = await resolve_public_principal(settings, request=request, authentication_adapter=None,
                                                    authorization="", anonymous_token="")
        if resolution.context.user_id and resolution.context.namespace == settings.namespace and resolution.anonymous_token:
            checks["anonymousSession"] = "ready"
    except Exception as exc:
        # 不把驱动异常正文中的连接信息和可能的凭据写入公开响应。
        message = str(exc)
        for secret in (settings.database.password, settings.model_api_key, settings.anonymous_session_secret):
            if secret:
                message = message.replace(secret, "[REDACTED]")
        logger.error("Runtime 就绪检查失败：%s: %s", type(exc).__name__, message[:1200])
    ready = all(value in {"ready", "not_required"} for value in checks.values())
    return JSONResponse(status_code=200 if ready else 503, content={
        "service": "agent-runtime", "launchId": settings.launch_id,
        "status": "ok" if ready else "error", "checks": checks,
    })
