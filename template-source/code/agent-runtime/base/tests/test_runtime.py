"""当前 Runtime 模板合同，不连接外部模型。"""
import pytest
from dataclasses import replace
from fastapi.testclient import TestClient
from app.settings import load_settings
from app.server.app import create_app


def test_health(monkeypatch):
    """验证真实路由就绪以及重启后旧 Cookie 的新身份恢复。"""
    monkeypatch.setenv("AGENT_RUNTIME_LAUNCH_ID", "test-launch")
    monkeypatch.setenv("AGENT_RUNTIME_NAMESPACE", "probe:test")
    monkeypatch.setenv("AGENT_RUNTIME_ANONYMOUS_SESSION_SECRET", "a" * 48)
    monkeypatch.setenv("AGENT_RUNTIME_DATABASE_ENABLED", "false")
    load_settings.cache_clear()
    with TestClient(create_app(load_settings())) as client:
        response = client.get("/health")
        assert response.status_code == 200
        assert response.json()["launchId"] == "test-launch"
        assert response.json()["checks"]["database"] == "not_required"
    load_settings.cache_clear()


@pytest.mark.asyncio
async def test_cookie_rotation_creates_new_trusted_identity():
    """验证旧签名失效后不接受旧身份，而是签发新 Cookie。"""
    from starlette.requests import Request
    from app.security.service import resolve_public_principal
    settings = load_settings()
    request = Request({"type": "http", "headers": []})
    first = await resolve_public_principal(settings, request=request, authentication_adapter=None,
                                           authorization="", anonymous_token="")
    rotated = replace(settings, anonymous_session_secret="another-test-only-key-that-is-long-enough")
    second = await resolve_public_principal(rotated, request=request, authentication_adapter=None,
                                            authorization="", anonymous_token=first.anonymous_token)
    assert second.context.user_id != first.context.user_id
    assert second.context.namespace == settings.namespace
    assert second.anonymous_token
