from __future__ import annotations

import base64
import hashlib
import hmac
import json
import time
from typing import Any
from uuid import uuid4

from app.settings import RuntimeSettings


class SecurityTokenError(ValueError):
    """表示签名会话凭据无效或已经过期。"""


def _encode(value: bytes) -> str:
    """生成无填充 URL-safe Base64 文本。"""

    return base64.urlsafe_b64encode(value).decode("ascii").rstrip("=")


def _decode(value: str) -> bytes:
    """解码无填充 URL-safe Base64 文本。"""

    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))


def issue_anonymous_session(
    settings: RuntimeSettings,
    *,
    user_id: str,
) -> str:
    """签发只在 Auth 关闭场景使用的匿名会话凭据。"""

    now = int(time.time())
    payload = {
        "v": 1,
        "typ": "anonymous",
        "namespace": settings.namespace,
        "sub": user_id,
        "sid": uuid4().hex,
        "iat": now,
        "exp": now + settings.anonymous_session_ttl_seconds,
    }
    encoded = _encode(
        json.dumps(payload, sort_keys=True, separators=(",", ":")).encode("utf-8")
    )
    signature = _encode(
        hmac.new(
            settings.require_anonymous_session_secret().encode("utf-8"),
            encoded.encode("utf-8"),
            hashlib.sha256,
        ).digest()
    )
    return f"{encoded}.{signature}"


def verify_anonymous_session(
    settings: RuntimeSettings,
    token: str,
) -> dict[str, Any]:
    """验证匿名会话的签名、版本、类型、有效期和身份字段。"""

    encoded, separator, signature = token.strip().partition(".")
    if separator != "." or not encoded or not signature or not token.isascii():
        raise SecurityTokenError("会话凭据无效。")
    expected_signature = _encode(
        hmac.new(
            settings.require_anonymous_session_secret().encode("utf-8"),
            encoded.encode("utf-8"),
            hashlib.sha256,
        ).digest()
    )
    if not hmac.compare_digest(signature, expected_signature):
        raise SecurityTokenError("会话凭据无效。")
    try:
        payload = json.loads(_decode(encoded).decode("utf-8"))
    except (UnicodeError, ValueError, json.JSONDecodeError) as exc:
        raise SecurityTokenError("会话凭据无效。") from exc
    if (
        not isinstance(payload, dict)
        or payload.get("v") != 1
        or payload.get("typ") != "anonymous"
        or payload.get("namespace") != settings.namespace
        or not isinstance(payload.get("sub"), str)
        or not payload["sub"]
        or not isinstance(payload.get("sid"), str)
        or not payload["sid"]
        or not isinstance(payload.get("exp"), int)
        or payload["exp"] <= int(time.time())
    ):
        raise SecurityTokenError("会话凭据无效或已经过期。")
    return payload
