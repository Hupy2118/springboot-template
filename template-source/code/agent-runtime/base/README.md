# Agent Runtime 基础组合

由 Template Engine 生成普通 Python 工程。Python 3.12，依赖用 uv.lock 冻结同步，入口 `uv run --no-sync --no-env-file agent-runtime`。

启动必须由当前 Studio 注入 LAUNCH_ID、NAMESPACE、匿名 Cookie 签名密钥、MODEL_* 和 AGENT_RUNTIME_DATABASE_*。不读取 dotenv 或旧 fallback；没有数据源时显式禁用业务库。业务存储为 MySQL，checkpoint SQLite 按 namespace 隔离；不自动创建业务表。

数据库事务：`async with context.database.transaction() as transaction`，`await transaction.execute(sql, parameters)`，参数占位 `%s`，游标 `await fetchone/fetchall` 返回字典行。只支持查询/DML。TLS 的 PREFERRED 在当前本地模板中同样要求加密，不允许隐式明文降级；验证模式使用系统 CA。

前端使用同源代理和官方 AG-UI 客户端。匿名 Cookie 重启失效会创建新身份，不重放业务消息，旧身份的数据不会转移给新身份。

当前登录/授权及独立 Debug Profile 扩展未开放；普通预览通过 `/docs` 调试，Agent 入口 `/agents/{agent_id}/run`。`/health` 返回本轮 launchId 和实际就绪检查，不调用模型。

MySQL 连接与事务源码由 python_business_mysql/files 独占，python_application_runtime 声明依赖它。直接维护 Base 时请通过 Engine 装配后运行，不直接把 Base 当完整工程。
