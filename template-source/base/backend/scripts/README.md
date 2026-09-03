# Java 权限 Bootstrap

脚本仅以加法方式确保以下数据存在：

- 固定 `SYSTEM_ADMIN` 角色；
- 已确认 `TechnicalPlan.authorization_manifest.resources` 中的资源；
- `SYSTEM_ADMIN -> system_authorization_management`；
- `application.json.authorization.initialAdministratorSubjects` 中的管理员成员关系。

脚本不会初始化 TechnicalPlan 的业务 `roleResourceGrants`，也不会更新或删除已有权限数据。
实现使用 Java 8、JDBC、项目已有 Jackson 和 MySQL Connector，不依赖 Python。

生成项目的目录结构必须为：

```text
<project>/.xcodeagent/application.json
<project>/.xcodeagent/plans/technical-plan.json
<project>/backend/docs/auth/sql/ddl.sql
<project>/backend/scripts/bootstrap-authorization.sh
```

在生成项目根目录执行输入校验，不连接数据库：

```bash
./backend/scripts/bootstrap-authorization.sh --dry-run
```

连接 MySQL 执行：

```bash
./backend/scripts/bootstrap-authorization.sh
```

Windows 使用等价入口：

```powershell
& .\backend\scripts\bootstrap-authorization.ps1
```

两个入口均从后端目录运行 Maven，成功返回 `0`；输入或业务校验失败返回 `2`，未预期错误返回 `3`，找不到 Maven 返回 `127`。

脚本优先加载 `backend/.env`，未提供的变量再使用
`backend/src/main/resources/application.yml` 中的 `spring.datasource.*` 默认值。
`application.yml` 的 `${DB_URL:default}`、`${DB_USERNAME:default}`、
`${DB_PASSWORD:default}` 按环境变量优先、默认值兜底的规则解析。

以 `.env.example` 为模板创建本地 `.env`，真实 `.env` 已被 Git 忽略，不得提交密码。
若四张权限表只存在一部分，脚本会 fail closed，不会补表或写入业务数据。
