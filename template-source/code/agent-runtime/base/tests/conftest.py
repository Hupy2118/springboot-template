"""仅为本地测试装配无外部资源的启动环境。"""
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
