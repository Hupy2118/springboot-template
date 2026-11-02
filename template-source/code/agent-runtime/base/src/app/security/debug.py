"""仅限独立 Debug Profile 的 loopback 能力凭据，身份由当前服务创建。"""
from __future__ import annotations
import hmac
import time
from fastapi import Request
from app.agent.context import RuntimeContext, RuntimeContextError
from app.settings import RuntimeSettings


def resolve_debug_principal(settings: RuntimeSettings, request: Request, authorization: str) -> RuntimeContext:
    """绑定当前进程、有效期及调试 namespace，拒绝匿名 Cookie 和客户端身份字段。"""
    supplied = authorization.removeprefix("Bearer ") if authorization.startswith("Bearer ") else ""
    if (settings.runtime_profile != "debug" or settings.namespace != "debug:" + settings.launch_id
            or request.client is None or request.client.host not in {"127.0.0.1", "::1"}
            or time.time() >= settings.debug_expires_at or not settings.debug_token
            or not hmac.compare_digest(supplied.encode(), settings.debug_token.encode())):
        raise RuntimeContextError("调试会话已失效或凭据无效，请重新开始调试。")
    return RuntimeContext(namespace=settings.namespace, user_id="debug:" + settings.launch_id,
                          authentication_mode="debug")
