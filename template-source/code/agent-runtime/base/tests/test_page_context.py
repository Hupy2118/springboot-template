"""当前页面上下文和只读交互状态查询回归，不访问模型或数据库。"""
import asyncio
from types import SimpleNamespace
import unittest

from app.agent.context import RuntimeContext
from app.interaction.schemas import parse_run_request, RuntimeRequestError
from app.interaction.service import _agent_input, stream_agent_interaction


class PageContextTests(unittest.TestCase):
    """官方 AG-UI 输入必须保留有界业务上下文，同时维护可信身份隔离。"""

    def request(self, props):
        """生成当前 SDK 会发送的完整输入。"""
        return parse_run_request({"threadId": "thread", "runId": "run", "state": {}, "tools": [], "context": [],
                                  "messages": [{"id": "question", "role": "user", "content": "查询这个订单"}],
                                  "forwardedProps": props})

    def test_context_reaches_messages_and_tools_without_changing_principal(self):
        """页面上下文进入普通消息和工具 context，但不能替换服务端用户身份。"""
        request = self.request({"pageId": "orders", "actionId": "ask", "contextItems": [
            {"id": "order", "label": "订单", "value": "ORDER-100"}], "userId": "forged"})
        messages = _agent_input(request=request, pending=None)["messages"]
        self.assertIn("ORDER-100", messages[-2]["content"])
        self.assertEqual(messages[-2]["role"], "user")
        self.assertEqual(messages[-1]["content"], "查询这个订单")
        agent = FakeAgent()
        context = RuntimeContext(namespace="preview:test", user_id="server-user", authentication_mode="anonymous")
        asyncio.run(self.collect(agent, request, context))
        self.assertEqual(agent.context.user_id, "server-user")
        self.assertEqual(agent.context.page_context.page_id, "orders")
        self.assertIsNone(context.page_context)

    def test_rejects_partial_oversized_or_ambiguous_query_input(self):
        """不完整上下文、类型不符及状态查询夹带批准都必须在请求边界拒绝。"""
        for props in ({"pageId": "orders"}, {"inspectInteraction": "true"},
                      {"inspectInteraction": True, "interactionResponse": {"interactionId": "i", "value": True}},
                      {"pageId": "orders", "actionId": "ask", "contextItems": [
                          {"id": "item", "label": "订单", "value": "x" * 4097}]}):
            with self.subTest(props=list(props)), self.assertRaises(RuntimeRequestError):
                self.request(props)

    def test_inspection_reads_pending_without_invoking_the_graph(self):
        """恢复状态查询只读 checkpoint，绝不调用 astream 或提交 resume。"""
        pending = {"schemaVersion": 1, "interactionId": "confirm", "kind": "confirmation",
                   "message": "确认修改？", "payload": {}}
        agent = FakeAgent(pending)
        context = RuntimeContext(namespace="preview:test", user_id="server-user", authentication_mode="anonymous")
        events = asyncio.run(self.collect(agent, self.request({"inspectInteraction": True}), context))
        self.assertEqual(agent.calls, 0)
        self.assertEqual(events[-1].status, "awaiting_user")
        self.assertEqual(events[-1].pending_interaction.interaction_id, "confirm")

    async def collect(self, agent, request, context):
        """消费真实交互服务的异步生成器。"""
        return [event async for event in stream_agent_interaction(agent=agent, agent_id="chat", request=request,
                                                                 runtime_context=context, execution_contract=TEST_EXECUTION_CONTRACT)]


class FakeAgent:
    """提供内存 checkpoint 与可计数的图调用边界。"""

    def __init__(self, pending=None):
        """保存唯一待交互项，不连接外部存储。"""
        self.pending, self.calls, self.context = pending, 0, None

    async def aget_state(self, _config):
        """返回与 LangGraph 一致的任务中断形状。"""
        return SimpleNamespace(tasks=[SimpleNamespace(interrupts=[SimpleNamespace(value=self.pending)])] if self.pending else [])

    async def astream(self, _input, *, config, context, stream_mode):
        """记录模型/工具实际消费的 context，避免把字段存在当作消费证据。"""
        self.calls += 1
        self.context = context
        yield ("messages", (SimpleNamespace(content="完成"), {}))


# 当前文本会话的显式测试合同；交互测试保留确认/澄清能力。
TEST_EXECUTION_CONTRACT = {"mode": "conversation", "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False}, "resultSchema": None, "textOutput": True, "interactionKinds": ["clarification", "confirmation", "approval_required"]}
