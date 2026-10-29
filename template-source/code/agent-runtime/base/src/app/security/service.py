"""从服务器签名 Cookie 建立可信身份，不接受客户端自报 Principal。"""
from __future__ import annotations
from dataclasses import dataclass
from uuid import uuid4
from fastapi import Request
from app.agent.context import RuntimeContext
from app.security.adapters import PublicAuthenticationAdapter, validate_authentication_settings
from app.security.tokens import SecurityTokenError, issue_anonymous_session, verify_anonymous_session
from app.settings import RuntimeSettings


@dataclass(frozen=True)
class PrincipalResolution:
    """保存可信上下文及需要写回的新 Cookie。"""
    context: RuntimeContext
    anonymous_token: str | None = None


async def resolve_public_principal(
    settings: RuntimeSettings, *, request: Request,
    authentication_adapter: PublicAuthenticationAdapter | None,
    authorization: str, anonymous_token: str, traceparent: str = "",
) -> PrincipalResolution:
    """恢复匿名会话；旧签名失效时签发新身份，不重新执行任何业务操作。"""
    validate_authentication_settings(settings)
    if anonymous_token:
        try:
            claims = verify_anonymous_session(settings, anonymous_token)
            return PrincipalResolution(RuntimeContext(
                user_id=claims["sub"], authentication_mode="anonymous",
                namespace=settings.namespace, traceparent=traceparent.strip(),
            ))
        except SecurityTokenError:
            # 重启轮换密钥后旧 Cookie 失效，创建新身份而不是沿用未验证 claims。
            pass
    user_id = uuid4().hex
    token = issue_anonymous_session(settings, user_id=user_id)
    return PrincipalResolution(RuntimeContext(
        user_id=user_id, authentication_mode="anonymous", namespace=settings.namespace,
        traceparent=traceparent.strip(),
    ), anonymous_token=token)
