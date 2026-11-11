"""仅为本地测试装配无外部资源的启动环境，并绑定实际服务配置。"""
import pytest
import uvicorn
import os

# 模块收集前建立测试身份；不提供真实模型 Key、不连接数据库。
os.environ["AGENT_RUNTIME_LAUNCH_ID"] = "template-test"
os.environ["AGENT_RUNTIME_NAMESPACE"] = "probe:template-test"
os.environ["AGENT_RUNTIME_PROFILE"] = "probe"
os.environ["AGENT_RUNTIME_HOST"] = "127.0.0.1"
os.environ["AGENT_RUNTIME_AUTH_ENABLED"] = "false"
os.environ["AGENT_RUNTIME_AUTH_MODE"] = "anonymous-session"
os.environ["AGENT_RUNTIME_DATABASE_ENABLED"] = "false"
os.environ["AGENT_RUNTIME_ANONYMOUS_SESSION_SECRET"] = "test-only-signing-secret-never-used-in-production"
os.environ["AGENT_RUNTIME_ALLOWED_ORIGINS"] = "[]"

import app.server.deployment as deployment


@pytest.fixture(autouse=True)
def controlled_runtime_configuration(monkeypatch):
    """TestClient 同样核验实际配置对象；生产入口必须自行绑定即将运行的配置。"""
    monkeypatch.setenv("AGENT_RUNTIME_WORKERS", "1")
    monkeypatch.setattr(deployment, "_server_pid", os.getpid())
    monkeypatch.setattr(deployment, "_server_config", uvicorn.Config(
        "app.server.app:app", host="127.0.0.1", workers=1, reload=False))


@pytest.fixture(autouse=True)
def current_text_policy(monkeypatch):
    """为离线模板测试显式签发当前纯文本策略，不让生产加载器提供 chat 默认值。"""
    import json
    from hashlib import sha256
    from pathlib import Path
    from langchain_core.language_models.fake_chat_models import FakeListChatModel
    import app.agent.execution_policy as policies
    import app.models.factory as models
    capability = {"mode": "conversation", "inputSchema": {"type": "object", "properties": {},
        "required": [], "additionalProperties": False}, "resultSchema": None, "textOutput": True, "publicStateSchema": None,
        "outcomes": [], "interactionKinds": []}
    entry = {"agentId": "chat", "apiContractId": "agent.chat", "endpointId": "agent.chat.run",
        "rule": "execution.policy.v2", "invocation": {"exposure": "public"}, "interactionContract": capability,
        "capabilitySha256": "sha256:" + "a" * 64, "implementationSha256": "sha256:" + "b" * 64,
        "budgets": {"requestBytes": 262144, "messages": 128, "userTextBytes": 16384, "textBytes": 131072,
            "threadRuns": 1, "identityRuns": 2, "processRuns": 4, "runSeconds": 120, "cleanupSeconds": 10}}
    entry["executionCapabilities"] = {"toolIds": [], "resourceFiles": {"skills": [], "knowledge": []}, "modelRequests": 1}
    entry["resourceSha256"] = {}
    monkeypatch.setenv("AGENT_RUNTIME_EXECUTION_POLICY_SHA256", sha256(Path(policies.__file__).read_bytes()).hexdigest())
    monkeypatch.setattr(policies, "POLICIES_JSON", json.dumps({"chat": entry}))
    from functools import lru_cache

    @lru_cache(maxsize=1)
    def offline_model(settings):
        """保留正式模型工厂的缓存清理合同，同时完全不连接供应商。"""
        return FakeListChatModel(responses=["离线测试文本"])

    monkeypatch.setattr(models, "create_chat_model", offline_model)
    import app.agent.invocation as invocation
    monkeypatch.setattr(invocation, "create_chat_model", offline_model)
