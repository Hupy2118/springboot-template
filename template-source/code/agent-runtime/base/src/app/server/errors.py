"""把实际异常转换为可见且不泄密的业务失败。"""
import json
from app.interaction.schemas import RuntimeRequestError
from app.settings import RuntimeSettings


def public_error(error: Exception, settings: RuntimeSettings) -> RuntimeRequestError:
    """保留模型状态和原因，清除本轮密钥、Header 及数据库密码。"""
    status = getattr(error, "status_code", None)
    text = str(error)
    for secret in (settings.model_api_key, settings.anonymous_session_secret,
                   settings.database.password, *json.loads(settings.model_custom_headers).values()):
        if secret:
            text = text.replace(secret, "[REDACTED]")
    # 只对已知模型 SDK 公开经过脱敏的错误正文；其他异常显示类型和定位建议。
    provider = type(error).__module__.split(".")[0]
    if provider not in {"openai", "anthropic", "httpx"} and not isinstance(error, (ValueError, RuntimeError)):
        text = "Runtime 执行异常：" + type(error).__name__ + "，请查看当前启动日志。"
    code = f"model_http_{status}" if isinstance(status, int) else "runtime_execution_failed"
    return RuntimeRequestError(code, text[:1600], recoverable=False)
