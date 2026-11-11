"""只校验输出合同边界和错误语义，不调用模型或外部服务。"""
import pytest
from langchain_core.messages import AIMessage
from pydantic import BaseModel
from app.agent.contracts import validate_contract
from app.interaction.service import _structured_result
from app.interaction.schemas import RuntimeRequestError
from app.server.errors import business_error_status

SCHEMA = {"type": "object", "properties": {"risk": {"type": "integer"}}, "required": ["risk"]}

class Detection(BaseModel):
    """提供模型结构化输出的真实对象形式。"""
    risk: int

def test_text_task_and_output_requirement():
    """纯文本任务可以运行，关闭文本又无结果时仍拒绝。"""
    contract = {"mode": "task", "inputSchema": {"type": "object"}, "resultSchema": None,
        "textOutput": True, "publicStateSchema": None, "outcomes": [], "interactionKinds": []}
    validate_contract(contract)
    with pytest.raises(RuntimeError):
        validate_contract({**contract, "textOutput": False})

def test_result_sources_and_invalid_explicit_result():
    """明确结果与 JSON 提取均受 Schema 约束，明确错误不能由文本覆盖。"""
    assert _structured_result({"structured_response": Detection(risk=1)}, SCHEMA) == {"risk": 1}
    assert _structured_result({"messages": [AIMessage(content='{"risk":2}')]}, SCHEMA) == {"risk": 2}
    with pytest.raises(RuntimeRequestError):
        _structured_result({"structured_response": {"risk": "bad"},
            "messages": [AIMessage(content='{"risk":2}')]}, SCHEMA)

@pytest.mark.parametrize("code,status", [("invalid_agent_input",422),("execution_limit",429),
    ("thread_busy",409),("run_timeout",504),("invalid_agent_result",502),
    ("runtime_unhealthy",503),("runtime_execution_failed",500)])
def test_error_status(code,status):
    """参数问题、运行故障和容量问题使用各自 HTTP 语义。"""
    assert business_error_status(RuntimeRequestError(code,"测试错误")) == status
