# Codex 实施方案 A｜模板服务 `1016`：Authorization SQL 路径归一

> 仓库：`https://github.com/Hupy2118/springboot-template.git`  
> 目标分支：`1016`（以执行时本地工作区为准）  
> 配套文档：`codex_xcodeagent_authorization_sql_path_fix.md`  
> 最终交付：**`backend/sql/authorization/schema.sql`**

## 0. 任务边界与已知事实

此任务是**在已实施上一次权限 Bootstrap 修复的本地代码上做增量变更**。不得依据尚未推送的远端 `1016` 覆盖、重写或撤销本地修改；先检查 `git status`、`git diff`、`git rev-parse HEAD`，识别本地已经存在的 `scripts/` 装载、Shell/PowerShell Bootstrap、Manifest v3、Bee/TDSQL、`.devagentstudio` 适配等成果。保留上述逻辑。

根据已公开的 `1016` 代码：

- Authorization DDL 源文件是 `template-source/code/backend/extensions/authorization/migrations/001-schema.sql`；Engine 投影为 `backend/migrations/001-schema.sql`。
- `BackendAssembler.assemble`、`CodeTemplateReleaseLoader.loadBackend`、`scripts/ci/list-code-template-release-inputs.py` 都以目录白名单装载 Extension 资源。**新增 `sql` 必须在这三个位置保持一致。**
- `CodeTemplateReleaseLoader.validateExtensionRoot` 独立校验根目录白名单，也必须加入 `sql`。
- `V3ProjectMaterializer` 按 `extension.backendFiles()` 将 Backend Extension 文件映射至 `backend/`；V4 更新从同一文件集合生成 `ADD_FILE`。**不需要新协议、专用 SQL 复制逻辑或改造 `V3UpdatePlanner`。**
- 本地上次修复可能已添加 `scripts` 支持和旧目标 `docs/auth/sql/ddl.sql`。先识别内容，不能保留多份相同 DDL。

**唯一源文件：**

```
template-source/code/backend/extensions/authorization/sql/authorization/schema.sql
```

**唯一新工程目标：**

```
backend/sql/authorization/schema.sql
```

不保留以下两条**权限 DDL 发布产物**：

```
backend/migrations/001-schema.sql
backend/docs/auth/sql/ddl.sql
```

注意：此处只是取消 Authorization 使用上述旧路径，**不得无关地删除 `migrations` / `docs` 目录的通用加载能力**。

## 1. P0 修改步骤

### A1｜移动 SQL 并保留内容

1. 比较本地可能存在的以下源文件：
   - `extensions/authorization/migrations/001-schema.sql`
   - `extensions/authorization/docs/auth/sql/ddl.sql`
   - `extensions/authorization/sql/authorization/schema.sql`
2. 选取现行准确的、已经过 Bootstrap/DDL 测试的 SQL 为权威内容；若不同，先输出 `diff` 和差异原因，禁止盲目覆盖、拼接或保留两份。
3. 使用 `git mv` 或等价方式将权威文件移动到 `extensions/authorization/sql/authorization/schema.sql`。保留建表定义、唯一索引、字段、执行顺序；**本任务不变更 SQL 的 DDL 语义**。
4. 删除 Authorization Extension 中已经废弃的旧 DDL 副本（如果确实存在）；其他 Capability 的 SQL 不受影响。

### A2｜后端模板维护 Assembly 纳入 `sql/`

文件：
`template-source/code/backend/assembly/src/main/java/com/cmbchina/template/backend/maintenance/BackendAssembler.java`

找到遍历 Extension 顶层资源目录的代码（远端原为 `Arrays.asList("src", "docs", "migrations")`），在**保留本地新增 `scripts`** 的前提下，追加 `"sql"`。

示意：

```java
for (String directory : Arrays.asList("src", "docs", "migrations", "scripts", "sql")) {
    // 继续复用现有 copyTree，保留所有权/冲突检查
}
```

这是示意而不是整段替换；以本地当前列表为准。`WorkspaceStatus.source`、`WorkspaceSync` 已基于 `state.owners` 和相对路径处理资源，通常无需专门添加 SQL 分支；通过新测试确认 `assemble`、`status`、`sync` 实际生效。

### A3｜Runtime Release Loader 纳入 `sql/`

文件：
`template-engine/engine-core/src/main/java/com/devagentstudio/template/engine/source/v3/CodeTemplateReleaseLoader.java`

1. `loadBackend()`：在遍历 `src/docs/migrations/...` 的加载目录中加入 `sql`，最终 `partial.backendFiles` 应包含 `sql/authorization/schema.sql`。
2. `validateExtensionRoot()`：明确允许 `sql` 目录，保留现有防符号链接/目录合法性检查，不能把整个 Extension 根目录无约束复制进 Release。
3. 若本地已经补过 `scripts`，不要删掉；确认脚本与 SQL 均进入同一 Release。
4. `V3ProjectMaterializer` 不需要单独注册 SQL；它自然投影为 `backend/sql/authorization/schema.sql`。
5. `V3UpdatePlanner.planCapabilities()` 不需要新 Operation：**首次安装 Authorization** 时，仍通过 `ADD_FILE` 投影 SQL；保留已有 `ENSURE_CAPABILITY_CONFIG`、`RECONCILE`、`204` 语义。

### A4｜发布输入与 Revision 门禁纳入 `sql/`

文件：
`scripts/ci/list-code-template-release-inputs.py`

在 Backend Extension 的目录枚举白名单中加入 `sql`，并保留本地已有 `scripts`。同步完善 `scripts/ci/test_code_template_release_inputs.py`：在测试目录建 `extensions/authorization/sql/authorization/schema.sql`，验证 `collect_inputs()` 将其包含；改变 SQL 文件能够触发 Revision 校验，软链接/非 UTF-8 内容仍被拒绝。

按仓库现有 Revision 规则更新 `template-source/code/template-revision.txt`，同时更新测试里写死的旧版本期望。**不要猜一个版本并跳过发布门禁。**

### A5｜Bootstrap Java 默认路径与脚本参数一致

文件：
`template-source/code/backend/extensions/authorization/src/main/java/com/cmbchina/backend/auth/bootstrap/AuthorizationBootstrapCommand.java`

将 `Arguments.parse` 中默认 DDL 文件从旧的 `docs/auth/sql/ddl.sql` 改为：

```java
Path ddl = backend.resolve("sql/authorization/schema.sql");
```

再检查本地上次新增的脚本：

```
template-source/code/backend/extensions/authorization/scripts/bootstrap-authorization.sh
template-source/code/backend/extensions/authorization/scripts/bootstrap-authorization.ps1
```

- 若其向 Java 传 `--ddl=...`，同步改成指向 `backend/sql/authorization/schema.sql` 的**真实绝对路径**（Java 启动 cwd 如为 backend，则可用 `sql/authorization/schema.sql`，但建议由脚本算出绝对路径以避免 cwd 歧义）。
- 若脚本使用 Java 默认值，也必须确认在 `backend` 目录执行，且与 `Arguments.parse` 一致。
- 保留 `--technical-plan`、`--application`、`--application-yml`、Bee/TDSQL 等上次已经修复的逻辑。不要恢复旧 `.xcodeagent` 或 `spring.datasource` 读取方式。
- 沿用已有 Maven/PowerShell 约束、Java MainClass 调用与非零退出码传播，不做无关重构。

### A6｜更新说明，清理旧路径约定

更新 `template-source/code/backend/extensions/authorization/docs/authorization.md`：说明 DDL 作为 Authorization 专属数据库资产投放到 `sql/authorization/schema.sql`；不是 `docs` 文档，也不意味着存在 Flyway/Liquibase 自动迁移。检查并清理其他活跃文档/测试中指向旧权限 DDL 的描述，但不要删除历史设计文档中的必要历史事实。

## 2. 必需测试与验收

| 验收场景 | 断言 |
|---|---|
| Base 单独组装 | 无 `sql/authorization/schema.sql`、无 Authorization Bootstrap 脚本 |
| Login 单独组装 | 无 `sql/authorization/schema.sql` |
| Authorization / Full Assembly | `workspace/sql/authorization/schema.sql` 存在，内容与 Extension 唯一源一致；旧权限 DDL 路径不存在 |
| `status/sync` | 修改 SQL 后能够识别 Authorization owner，`sync authorization` 正确写回，不误改 Base / Login |
| `CodeTemplateReleaseLoaderTest` | Release/backendFiles 包含 `sql/authorization/schema.sql`；生成工程包含 `backend/sql/authorization/schema.sql` |
| `EngineServiceIT` Generate | Authorization ZIP 含 `backend/sql/authorization/schema.sql` 和本地已有两平台脚本，**不含**两个旧 DDL 路径 |
| `EngineServiceIT` V4 安装 | Base→Authorization、Login→Authorization 的更新包中存在 `ADD_FILE target=backend/sql/authorization/schema.sql`，`payloadRef` 对应完整原始 SQL 字节；`nextTemplateState.installedArtifacts` 记录新路径 |
| `EngineServiceIT` 重复安装 | 安装完成后重复 APPLY 仍为 204，不因本次改动升级已安装 Capability |
| Release 输入门禁 | `sql/authorization/schema.sql` 被收集，SQL 修改需要 Revision 更新 |
| Bootstrap 命令 | 从生成工程 `backend/` 执行时加载新路径；缺失/无效 DDL 返回非零，且不会误读取旧文件 |

建议增加一个**明确的对称性测试**：同一 Capability 配置的 `Generate` 产物 SQL 字节 == V4 `ADD_FILE` Payload 字节。

可以优先执行（按本地仓库现有命令与 Maven 私库环境调整）：

```bash
cd template-source/code/backend
./backend-template reset authorization
./backend-template verify-update authorization

cd ../../../
python3 -m unittest discover -s scripts/ci -p 'test_code_template_release_inputs.py'
mvn -f template-engine/pom.xml -pl engine-service -am test
```

如 Maven 的行内依赖无法下载，提供命令输出和静态/单测已完成范围，不得宣称端到端通过。

## 3. 历史已安装工程的明确边界

新生成或**首次** V4 安装 Authorization 的工程会得到新 SQL 路径。对于 `TemplateState.effective` 已经包含 `authorization` 的旧工程，当前 V4 APPLY 返回 204，RECONCILE 也不负责重放普通 `installedArtifacts`。**不得通过隐式升级或手改 TemplateState 来制造“修复成功”的假象。**

默认不在本 PR 做旧工程兼容和批量迁移。需验证实际运行时，可创建全新测试工程；保留中的老工程应由独立、显式、可审计的资产迁移流程处理。若必须现场临时排障，可以在备份后将旧 SQL *复制* 到新约定路径验证 Bootstrap，但应明确标注为人工临时修复，且不代表引擎资产状态已迁移。

## 4. 明确禁止

- 不为某一个 SQL 增加新 V4 Operation、专用 Injector、旧的 V2 strategy/anchor。
- 不将 SQL 写入 Base、Login，也不再以 `docs/auth/sql/ddl.sql` 作为交付物。
- 不在 XCodeAgent 二次生成数据库表结构，也不引入 Flyway/Liquibase。
- 不删除其他 Capability 可能使用的通用 `migrations` 装载能力。
- 不更改数据库连接、YHT 配置值、权限资源业务逻辑、前端路由或其他 Extension。
- 不在本地已完成的 Bootstrap 修复上回退代码。

## 5. 提交交付物

给出修改文件清单、DDL 原文件/目标文件内容或 SHA-256 对照、`Generate` ZIP 路径列表、V4 ADD_FILE operation/payload 验证结果、实际测试命令与通过/未通过情况、Revision 变化。
