"""预留认证能力的类型边界，当前模板只开放匿名会话。"""
from __future__ import annotations
from typing import Protocol
from app.settings import RuntimeSettings


class PublicAuthenticationAdapter(Protocol):
    """预留未来企业身份适配器；当前无具体实现。"""
    async def authenticate(self, request, bearer_token):
        """由未来登录能力实现可信身份恢复。"""
        ...


def validate_authentication_settings(settings: RuntimeSettings) -> None:
    """明确拒绝尚未实现的登录场景。"""
    if settings.auth_enabled or settings.auth_mode != "anonymous-session":
        raise RuntimeError("当前 Runtime 模板仅支持 anonymous-session。")


def create_public_authentication_adapter(settings: RuntimeSettings):
    """匿名组合不创建认证适配器，扩展开放前保持严格拒绝。"""
    validate_authentication_settings(settings)
    return None
