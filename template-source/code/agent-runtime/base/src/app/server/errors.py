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


def business_error_status(error: RuntimeRequestError) -> int:
    """按失败责任返回 HTTP 状态，不能把超时、容量或内部故障伪装为参数错误。"""
    if getattr(error, "pending", None) is not None:
        return 409
    code = error.code
    statuses = {"thread_busy": 409, "interaction_conflict": 409, "interaction_response_required": 409,
        "stale_interaction_response": 409, "duplicate_message": 409,
        "execution_limit": 429, "run_timeout": 504, "runtime_unhealthy": 503,
        "agent_not_found": 404, "invalid_agent_result": 502, "agent_result_missing": 502,
        "output_limit": 502, "supplier_budget_exhausted": 502, "undeclared_interaction": 502,
        "invalid_pending_interaction": 502, "multiple_pending_interactions": 502,
        "agent_mapping_source_missing": 422, "invalid_agent_input": 422, "message_required": 422,
        "invalid_interaction_value": 422, "untrusted_history": 422, "history_limit": 422}
    if code.startswith("model_http_"):
        return 502
    return statuses.get(code, 500)
