"""公开文本会话与内部能力分开校验；仅允许正式工具和精确只读资源。"""
from hashlib import sha256
from pathlib import Path
from langchain.agents.middleware import AgentMiddleware
from langchain_core.tools import tool
from app.agent.execution_policy import ExecutionPolicy
from app.interaction.schemas import RuntimeRequestError


class NoFilesystem(AgentMiddleware):
    """用当前 DeepAgents 支持的同名中间件替换取消文件工具装配。"""
    name = "FilesystemMiddleware"
    tools = []


class NoSubagents(AgentMiddleware):
    """移除默认 task 工具和子 Agent 分派，保持公开能力为空。"""
    name = "SubAgentMiddleware"
    tools = []


class NoSummarization(AgentMiddleware):
    """禁用额外总结模型调用，所有会话仅使用服务端 checkpoint 的有界历史。"""
    name = "SummarizationMiddleware"
    tools = []


class NoTodo(AgentMiddleware):
    """禁止旧默认 Todo 工具被模型使用。"""
    name = "TodoListMiddleware"
    tools = []


class TextOnlyGuard(AgentMiddleware):
    """文本输出不限制内部模块，但每次模型调用都复验正式工具及资源。"""

    def __init__(self, policy: ExecutionPolicy):
        """只接收平台创建的冻结策略。"""
        if not isinstance(policy, ExecutionPolicy):
            raise RuntimeError("缺少可信执行策略。")
        self.policy = policy

    def _request(self, request):
        """不隐式裁掉实际注册工具；发现能力扩张时明确失败。"""
        names = [_tool_name(item) for item in request.tools]
        if (len(set(names)) != len(names) or set(names) != allowed_tool_names(self.policy)
                or request.response_format is not None):
            raise RuntimeRequestError("undeclared_capability", "实际工具与正式声明不一致，或使用了未声明的结构化输出。")
        validate_resources(self.policy)
        from app.server.execution import current_execution
        execution = current_execution.get()
        if execution is not None:
            execution.provider_limit = min(execution.provider_limit, self.policy.model_requests)

    def _response(self, response):
        """在 Agent 状态更新和工具路由之前拒绝模型发出的任何工具意图。"""
        for message in response.result:
            if getattr(message, "invalid_tool_calls", None) or any(
                    call.get("name") not in allowed_tool_names(self.policy) for call in getattr(message, "tool_calls", ())):
                raise RuntimeRequestError("undeclared_tool_call", "当前 Agent 未声明该工具能力，调用已被阻止。")
        return response

    def wrap_model_call(self, request, handler):
        """同步模型遵守同一请求和输出能力门禁。"""
        self._request(request)
        return self._response(handler(request))

    async def awrap_model_call(self, request, handler):
        """异步模型遵守同一请求和输出能力门禁。"""
        self._request(request)
        return self._response(await handler(request))


def text_only_middleware(policy: ExecutionPolicy) -> list:
    """当前锁定 DeepAgents 版本按公开同名替换机制裁掉默认工具中间件。"""
    return [NoFilesystem(), NoSubagents(), NoSummarization(), NoTodo(), TextOnlyGuard(policy)]


def verify_text_graph(graph, policy: ExecutionPolicy):
    """复核实际编译节点，按正式内部能力开放工具及资源钩子。"""
    if not isinstance(policy, ExecutionPolicy):
        raise RuntimeError("Agent 缺少不可变执行策略。")
    allowed = {"__start__", "model", "PatchToolCallsMiddleware.before_agent"}
    if allowed_tool_names(policy):
        allowed.add("tools")
    if any(path.startswith("agent-runtime/src/app/skills/") for path in policy.resource_files):
        allowed.add("SkillsMiddleware.before_agent")
    if any(path.startswith("agent-runtime/src/app/knowledge/") for path in policy.resource_files):
        allowed.add("MemoryMiddleware.before_agent")
    if not hasattr(graph, "nodes") or not set(graph.nodes) <= allowed or "model" not in graph.nodes:
        raise RuntimeError("Agent 实际图不满足正式能力的受控节点集合，禁止启动。")
    if not policy.interaction_kinds and (getattr(graph, "interrupt_before_nodes", ()) or getattr(graph, "interrupt_after_nodes", ())):
        raise RuntimeError("Agent 实际图包含未声明中断。")
    validate_resources(policy)
    return graph


def _tool_name(value) -> str:
    """支持框架工具对象和官方字典描述，不接受无法识别的动态工具。"""
    if isinstance(value, dict):
        return value.get("name") or (value.get("function") or {}).get("name") or ""
    return getattr(value, "name", "")


def allowed_tool_names(policy: ExecutionPolicy) -> set[str]:
    """正式业务 Tool 加受控技能文档读取，不开放默认文件写入或子代理工具。"""
    names = set(policy.tool_ids)
    if any(path.startswith("agent-runtime/src/app/skills/") for path in policy.resource_files):
        if "read_file" in names:
            raise RuntimeError("业务工具不能占用技能资源的 read_file 名称。")
        names.add("read_file")
    return names


def _resource_path(relative: str) -> Path:
    """资源只能位于 Runtime 自身的正式 src/app 子目录，逐段拒绝链接。"""
    root = Path(__file__).resolve().parents[1]
    suffix = relative.removeprefix("agent-runtime/src/app/")
    path = root
    for part in Path(suffix).parts:
        path /= part
        if path.is_symlink():
            raise RuntimeError("Agent 资源不能经过符号链接。")
    return path


def validate_resources(policy: ExecutionPolicy) -> None:
    """启动及发送模型前核对实际文档摘要，防止正式资源被替换后沿用旧策略。"""
    for relative in policy.resource_files:
        path = _resource_path(relative)
        if not path.is_file() or path.stat().st_size > 65536 or sha256(path.read_bytes()).hexdigest() != policy.resource_sha256[relative]:
            raise RuntimeError("Agent 正式资源缺失、过大或已变化，请重新构建确认。")


def resource_tools(policy: ExecutionPolicy) -> list:
    """为 Skills 的按需读取提供精确文件白名单，不恢复任意文件系统工具。"""
    files = {"/" + path.removeprefix("agent-runtime/src/app/skills/"): path for path in policy.resource_files
             if path.startswith("agent-runtime/src/app/skills/")}
    if not files:
        return []

    @tool
    def read_file(file_path: str, offset: int = 0, limit: int = 200) -> str:
        """读取当前 Agent 正式声明的技能文档，拒绝其他文件和越界分页。"""
        if file_path not in files or type(offset) is not int or type(limit) is not int or offset < 0 or not 1 <= limit <= 400:
            raise RuntimeRequestError("undeclared_resource", "只能读取当前 Agent 已声明的技能文档。")
        validate_resources(policy)
        return "\n".join(_resource_path(files[file_path]).read_text(encoding="utf-8").splitlines()[offset:offset + limit])

    return [read_file]
