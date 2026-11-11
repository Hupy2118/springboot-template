"""Runtime 共享的正式执行合同和自包含 JSON Schema 校验。"""

import json
from typing import Any

from jsonschema import Draft202012Validator
from jsonschema.exceptions import SchemaError, ValidationError

from app.interaction.schemas import RuntimeRequestError


def validate_schema(schema: dict, *, object_input: bool = False) -> None:
    """拒绝远程引用和不合法 Schema，避免执行时读取声明范围以外的资源。"""
    if not isinstance(schema, dict) or len(json.dumps(schema).encode()) > 65536:
        raise RuntimeError("Agent Schema 缺失或超过 64 KiB。")
    if object_input and schema.get("type") != "object":
        raise RuntimeError("Agent inputSchema 必须声明 object。")
    pending = [schema]
    while pending:
        value = pending.pop()
        if isinstance(value, dict):
            if any(key in value and (not isinstance(value[key], str) or not value[key].startswith("#"))
                   for key in ("$ref", "$dynamicRef")):
                raise RuntimeError("Agent Schema 只允许本地片段引用。")
            pending.extend(value.values())
        elif isinstance(value, list):
            pending.extend(value)
    try:
        Draft202012Validator.check_schema(schema)
    except SchemaError as exc:
        raise RuntimeError("Agent JSON Schema 无效。") from exc


def validate_contract(value: Any) -> dict:
    """严格复验平台签发的任务/会话能力，不通过缺省值扩大能力。"""
    keys = {"mode", "inputSchema", "resultSchema", "textOutput", "publicStateSchema", "outcomes", "interactionKinds"}
    if (not isinstance(value, dict) or set(value) != keys or value["mode"] not in {"task", "conversation"}
            or type(value["textOutput"]) is not bool or value["publicStateSchema"] is not None or value["outcomes"] != []):
        raise RuntimeError("Agent 执行能力合同无效。")
    kinds = value["interactionKinds"]
    if (not isinstance(kinds, list) or any(not isinstance(kind, str) for kind in kinds)
            or len(kinds) != len(set(kinds)) or set(kinds) - {"clarification", "confirmation", "approval_required"}):
        raise RuntimeError("Agent 交互能力无效。")
    validate_schema(value["inputSchema"], object_input=True)
    if value["resultSchema"] is not None:
        validate_schema(value["resultSchema"])
    elif not value["textOutput"]:
        raise RuntimeError("关闭文本输出时必须声明 resultSchema。")
    return value


def validate_value(schema: dict, value: Any, *, result: bool = False) -> None:
    """仅把实际通过正式 Schema 的业务值交付给调用者，不修补或重试模型结果。"""
    try:
        Draft202012Validator(schema).validate(value)
    except ValidationError as exc:
        code = "invalid_agent_result" if result else "invalid_agent_input"
        raise RuntimeRequestError(code, "Agent 结果不符合正式合同。" if result else "Agent 输入不符合正式合同。") from exc
