# Agent Runtime 基础组合

由 Template Engine 生成普通 Python 工程。Python 3.12，依赖用 uv.lock 冻结同步，入口 `uv run --no-sync --no-env-file agent-runtime`。

启动必须由当前 Studio 注入 LAUNCH_ID、NAMESPACE、匿名 Cookie 签名密钥、MODEL_* 和 AGENT_RUNTIME_DATABASE_*。不读取 dotenv 或旧 fallback；没有数据源时显式禁用业务库。业务存储为 MySQL，checkpoint SQLite 按 namespace 隔离；不自动创建业务表。

启动同时要求 `AGENT_RUNTIME_WORKERS=1`；受控入口固定单 worker，并在开放路由前取得 checkpoint 命名空间内核排他锁。`/health.deployment` 返回实际持锁事实及应用/namespace 摘要。另一个竞争同一存储的进程启动失败；不支持多 worker、跨主机或脱离受控启动器的部署。

线程 run/resume/query 互斥且不排队；每可信身份最多 2 个业务执行、每进程最多 4 个，只读查询不创建模型。请求原始字节最多 256 KiB、消息最多 128 条、新 user 文本最多 16 KiB UTF-8、公开回答最多 128 KiB。澄清只能提交非空有界文本，确认/批准只能提交严格布尔值。

整个请求最长 120 秒，含 checkpoint、图和流式发送背压，模型单次 HTTP timeout 不得超过剩余期限。超时/断流先取消并等待实际工作退出，10 秒清理仍未收口则保留占用、health 不健康，并通知持续消费日志的 Studio 受控启动器回收当前进程一次。无自动重启/业务重放；未收到完整终态时结果未知。自建 detached task 或未等待真实同步工作的扩展不属于执行收口保证。完整维护规则见仓库 `docs/DEVELOPMENT_GUIDE.md`。

数据库事务：`async with context.database.transaction() as transaction`，`await transaction.execute(sql, parameters)`，参数占位 `%s`，游标 `await fetchone/fetchall` 返回字典行。只支持查询/DML。TLS 的 PREFERRED 在当前本地模板中同样要求加密，不允许隐式明文降级；验证模式使用系统 CA。

前端使用同源代理和官方 AG-UI 客户端。匿名 Cookie 重启失效会创建新身份，不重放业务消息，旧身份的数据不会转移给新身份。

登录/授权暂未开放。普通预览保留匿名 Cookie；Studio 显式调试启动独立 debug Profile，注入本轮 AGENT_RUNTIME_DEBUG_TOKEN、AGENT_RUNTIME_DEBUG_EXPIRES_AT，namespace 固定 debug:<launchId>，十分钟过期。只允许 loopback 和 Bearer 凭据，不能用匿名 Cookie 或自报 Principal 进入调试。停止或重启使原凭据失效。业务 Agent 入口 `/agents/{agent_id}/run`，使用官方 AG-UI 生命周期和确认恢复；凭据不进入普通预览或状态快照。`/health` 返回当前 profile、launchId、实际 agentIds 及就绪检查，不调用模型或业务写操作。

MySQL 连接与事务源码由 python_business_mysql/files 独占，python_application_runtime 声明依赖它。直接维护 Base 时请通过 Engine 装配后运行，不直接把 Base 当完整工程。


## 纯文本运行合同（2026.10.31.1）

Runtime 必须由 Studio 在已确认 DAG 的平台投影阶段交付 execution_policy.py 区域及进程摘要；缺失、过期或实际 Agent 集合不符时拒绝启动。实际图只允许受控文本节点，默认文件、task、Todo 和总结能力被替换；请求不接受系统/工具角色、非空 state/tools/context、结构化输入或交互续接。历史来自服务端 checkpoint，客户端只提交新 user 尾项；镜像历史不重复写入。

目标验收使用独立 probe namespace，SDK 重试为零，真实 HTTP 请求预算为每授权 case 一次；失败或未知不退还预算。普通 Preview 保持完整注册集合门禁。此改动尚未执行构建、离线回归或真实供应商/Electron 验收，不能据源码存在宣称通过。


## 会话公开合同与内部模块修正（2026.10.31.2）

公开文本会话不再要求关闭 Tool、Skill 或 Knowledge。Studio 按正式七模块交付 executionCapabilities（业务 Tool 名称、精确资源清单、模型请求上限）和文档字节摘要。执行层允许已声明业务 Tool 及受控技能 read_file，禁止任意文件写入、子代理与未声明工具；SkillsMiddleware/MemoryMiddleware 使用独立只读资源根及精确来源，平台只读加载文件不等同于开放默认文件系统。交互仅允许 interactionKinds 中显式声明的类型，写入工具仍在实际执行前确认。无工具/技能时每 Run 最多一次模型发送，有业务工具或技能时最多八次；SDK 重试同样计数。每授权验收 case 只发起一次会话，不自动重放该会话。内部工具循环产生的公开助手正文按同一用户轮次合并校验历史。

本次未做真实供应商或 Electron 验收，代码写入不代表功能验收通过。
