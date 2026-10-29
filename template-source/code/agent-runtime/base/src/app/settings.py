"""只读取本轮启动环境，不从 dotenv 或历史配置回退。"""
from __future__ import annotations

import hashlib
import json
import math
import os
from dataclasses import dataclass, field
from functools import lru_cache
from pathlib import Path

from app.infrastructure.database_settings import DatabaseSettings, load_database_settings

RUNTIME_ROOT = Path(__file__).resolve().parents[2]


def boolean(name: str, default: bool = False) -> bool:
    """严格读取环境布尔值，错误配置立即暴露。"""
    value = os.getenv(name, str(default)).strip().lower()
    if value not in {"true", "false"}:
        raise RuntimeError(f"{name} 必须为 true 或 false。")
    return value == "true"


def number(name: str, default: str, *, integer: bool = False, minimum: float = 0):
    """读取有限数值并拒绝越界值。"""
    try:
        value = int(os.getenv(name, default)) if integer else float(os.getenv(name, default))
    except ValueError:
        raise RuntimeError(f"{name} 数值无效。") from None
    if not math.isfinite(value) or value < minimum:
        raise RuntimeError(f"{name} 数值越界。")
    return value


def allowed_origins() -> tuple[str, ...]:
    """读取 JSON Origin 白名单；同源代理默认无需 CORS。"""
    try:
        values = json.loads(os.getenv("AGENT_RUNTIME_ALLOWED_ORIGINS", "[]"))
    except ValueError:
        raise RuntimeError("AGENT_RUNTIME_ALLOWED_ORIGINS 必须为 JSON 数组。") from None
    if not isinstance(values, list) or any(not isinstance(item, str) or not item.startswith(("http://", "https://")) or "*" in item for item in values):
        raise RuntimeError("AGENT_RUNTIME_ALLOWED_ORIGINS 不允许通配或非法 Origin。")
    return tuple(dict.fromkeys(values))


@dataclass(frozen=True)
class RuntimeSettings:
    """保存当前模板注入合同，密钥不进入 repr。"""
    runtime_root: Path
    host: str
    port: int
    working_dir: Path
    launch_id: str
    namespace: str
    runtime_profile: str
    auth_enabled: bool
    auth_mode: str
    anonymous_session_secret: str = field(repr=False)
    anonymous_session_ttl_seconds: int
    anonymous_cookie_name: str
    cookie_secure: bool
    allowed_origins: tuple[str, ...]
    model_provider: str
    model_base_url: str
    model_api_key: str = field(repr=False)
    model_name: str
    model_custom_headers: str = field(repr=False)
    model_trust_env: bool
    anthropic_api_version: str
    model_timeout_seconds: float
    model_max_retries: int
    model_temperature: float
    model_max_tokens: int
    database: DatabaseSettings = field(repr=False)

    @property
    def checkpoint_db_path(self) -> Path:
        """按可信 namespace 隔离 checkpoint，业务数据不存入此文件。"""
        namespace = hashlib.sha256(self.namespace.encode()).hexdigest()
        return self.working_dir / namespace / "checkpoints.sqlite"

    def require_anonymous_session_secret(self) -> str:
        """拒绝使用缺失或过短的匿名签名密钥。"""
        if len(self.anonymous_session_secret) < 32:
            raise RuntimeError("AGENT_RUNTIME_ANONYMOUS_SESSION_SECRET 至少需要 32 个字符。")
        return self.anonymous_session_secret

    def require_model(self) -> None:
        """在模型调用前检查实际注入，保留提供商失败原因为业务错误。"""
        if self.model_provider not in {"openai", "anthropic"}:
            raise RuntimeError("MODEL_PROVIDER 只支持 openai 或 anthropic。")
        if not all((self.model_name, self.model_api_key, self.model_base_url)):
            raise RuntimeError("MODEL_NAME、MODEL_API_KEY、MODEL_BASE_URL 未完整注入。")


@lru_cache(maxsize=1)
def load_settings() -> RuntimeSettings:
    """从当前环境加载配置，要求调用方提供启动身份与隔离域。"""
    launch_id = os.getenv("AGENT_RUNTIME_LAUNCH_ID", "").strip()
    namespace = os.getenv("AGENT_RUNTIME_NAMESPACE", "").strip()
    host = os.getenv("AGENT_RUNTIME_HOST", "127.0.0.1").strip()
    profile = os.getenv("AGENT_RUNTIME_PROFILE", "preview").strip()
    if not launch_id or not namespace or host != "127.0.0.1" or profile not in {"preview", "probe"}:
        raise RuntimeError("Runtime 需要本轮 LAUNCH_ID、NAMESPACE、loopback HOST 和 preview/probe PROFILE。")
    auth_enabled = boolean("AGENT_RUNTIME_AUTH_ENABLED")
    auth_mode = os.getenv("AGENT_RUNTIME_AUTH_MODE", "anonymous-session")
    if auth_enabled or auth_mode != "anonymous-session":
        raise RuntimeError("当前 Runtime 模板仅支持 anonymous-session，登录/授权扩展尚未开放。")
    headers = os.getenv("MODEL_CUSTOM_HEADERS", "{}")
    try:
        parsed = json.loads(headers)
    except ValueError:
        raise RuntimeError("MODEL_CUSTOM_HEADERS 必须为 JSON 对象。") from None
    if not isinstance(parsed, dict) or any(not isinstance(k, str) or not isinstance(v, str) for k, v in parsed.items()):
        raise RuntimeError("MODEL_CUSTOM_HEADERS 的键和值必须为文本。")
    work = Path(os.getenv("AGENT_RUNTIME_WORKING_DIR", ".agent-runtime")).expanduser()
    port = number("AGENT_RUNTIME_PORT", "8010", integer=True, minimum=1)
    if port > 65535:
        raise RuntimeError("AGENT_RUNTIME_PORT 超出端口范围。")
    settings = RuntimeSettings(
        runtime_root=RUNTIME_ROOT, host=host, port=port,
        working_dir=(work if work.is_absolute() else RUNTIME_ROOT / work).resolve(),
        launch_id=launch_id, namespace=namespace, runtime_profile=profile,
        auth_enabled=auth_enabled, auth_mode=auth_mode,
        anonymous_session_secret=os.getenv("AGENT_RUNTIME_ANONYMOUS_SESSION_SECRET", ""),
        anonymous_session_ttl_seconds=number("AGENT_RUNTIME_ANONYMOUS_SESSION_TTL_SECONDS", "86400", integer=True, minimum=1),
        anonymous_cookie_name=os.getenv("AGENT_RUNTIME_ANONYMOUS_COOKIE_NAME", "agent_runtime_session"),
        cookie_secure=boolean("AGENT_RUNTIME_COOKIE_SECURE"), allowed_origins=allowed_origins(),
        model_provider=os.getenv("MODEL_PROVIDER", ""), model_base_url=os.getenv("MODEL_BASE_URL", ""),
        model_api_key=os.getenv("MODEL_API_KEY", ""), model_name=os.getenv("MODEL_NAME", ""),
        model_custom_headers=headers, model_trust_env=boolean("MODEL_TRUST_ENV"),
        anthropic_api_version=os.getenv("ANTHROPIC_API_VERSION", "2023-06-01"),
        model_timeout_seconds=number("MODEL_TIMEOUT_SECONDS", "120", minimum=0.001),
        model_max_retries=number("MODEL_MAX_RETRIES", "2", integer=True),
        model_temperature=number("AGENT_TEMPERATURE", "0.2"),
        model_max_tokens=number("AGENT_MAX_TOKENS", "4096", integer=True, minimum=1),
        database=load_database_settings(),
    )
    settings.require_anonymous_session_secret()
    return settings


def clear_settings_cache() -> None:
    """供显式重载及测试清理设置缓存。"""
    load_settings.cache_clear()
