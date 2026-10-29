"""为 Anthropic 显式应用本轮 HTTP 客户端，禁止宿主代理配置越权。"""
from functools import cached_property
import anthropic
import httpx
from langchain_anthropic import ChatAnthropic
from pydantic import Field


class RuntimeChatAnthropic(ChatAnthropic):
    """沿 LangChain 模型协议只替换传输客户端创建。"""
    runtime_http_client: httpx.Client = Field(exclude=True)
    runtime_http_async_client: httpx.AsyncClient = Field(exclude=True)

    @cached_property
    def _client(self):
        """使用已注入 trust_env/timeout 的同步客户端。"""
        return anthropic.Client(**self._client_params, http_client=self.runtime_http_client)

    @cached_property
    def _async_client(self):
        """使用同一轮配置的异步客户端。"""
        return anthropic.AsyncClient(**self._client_params, http_client=self.runtime_http_async_client)
