"""按 Studio 当前注入创建真实模型，不使用模型别名或历史 fallback。"""
from __future__ import annotations

import json
from functools import lru_cache

import httpx
from langchain.chat_models import init_chat_model
from langchain_core.language_models.chat_models import BaseChatModel
from pydantic import SecretStr

from app.settings import RuntimeSettings
from app.models.anthropic_transport import RuntimeChatAnthropic

_CLIENTS: list[tuple[httpx.Client, httpx.AsyncClient]] = []


@lru_cache(maxsize=1)
def create_chat_model(settings: RuntimeSettings) -> BaseChatModel:
    """显式传递 provider、API 名和传输参数，错误保留给 AG-UI 失败事件。"""
    settings.require_model()
    headers = json.loads(settings.model_custom_headers)
    if settings.model_provider == "anthropic":
        headers["anthropic-version"] = settings.anthropic_api_version
    sync_client = httpx.Client(trust_env=settings.model_trust_env, timeout=settings.model_timeout_seconds)
    async_client = httpx.AsyncClient(trust_env=settings.model_trust_env, timeout=settings.model_timeout_seconds)
    _CLIENTS.append((sync_client, async_client))
    if settings.model_provider == "anthropic":
        return RuntimeChatAnthropic(
            model=settings.model_name, api_key=SecretStr(settings.model_api_key), base_url=settings.model_base_url,
            temperature=settings.model_temperature, max_tokens=settings.model_max_tokens,
            timeout=settings.model_timeout_seconds, max_retries=settings.model_max_retries,
            default_headers=headers, streaming=True,
            runtime_http_client=sync_client, runtime_http_async_client=async_client,
        )
    return init_chat_model(
        model=settings.model_name, model_provider=settings.model_provider,
        api_key=SecretStr(settings.model_api_key), base_url=settings.model_base_url,
        temperature=settings.model_temperature, max_tokens=settings.model_max_tokens,
        timeout=settings.model_timeout_seconds, max_retries=settings.model_max_retries,
        default_headers=headers, http_client=sync_client, http_async_client=async_client,
        streaming=True,
    )


async def close_chat_models() -> None:
    """退出时关闭当前工厂创建的网络客户端。"""
    create_chat_model.cache_clear()
    while _CLIENTS:
        sync_client, async_client = _CLIENTS.pop()
        sync_client.close()
        await async_client.aclose()
