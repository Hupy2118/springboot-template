# Authorization SQL 路径归一实施记录

执行日期：2026-10-23（Asia/Shanghai）。本地分支 `1016`，起始 HEAD `81a33bfd88b6cf550aaece3000b4a9334fa528ed`。

## 结果与边界

已在现有未提交的 Bootstrap 修复上增量实施，保留 scripts 装载、双平台脚本、Manifest v3、Bee/TDSQL 与 `.devagentstudio` 逻辑。未提交或推送 Git。

唯一源文件为 `template-source/code/backend/extensions/authorization/sql/authorization/schema.sql`，新生成工程及 V4 首次安装唯一目标为 `backend/sql/authorization/schema.sql`。Authorization 不再发布 `backend/migrations/001-schema.sql` 或 `backend/docs/auth/sql/ddl.sql`。通用 migrations/docs/scripts 装载能力仍保留。

反向验证：携带旧 docs 路径的已安装 Authorization State，在当前 Release 下 APPLY 仍为 NO_CHANGE，RECONCILE 仍拒绝无状态变化的请求；本次不隐式升级或迁移旧工程。这也意味着已安装工程不能靠重复 APPLY 获得新路径。

Revision：起始本地 `2026.10.23.1` → `2026.10.23.2`；HEAD 基线为 `2026.10.22.2`。已通过基于 HEAD 的发布门禁，并用临时 Git 仓库验证仅修改 SQL、未增加 Revision 时会失败。

## 本次增量文件清单

| 文件（仓库相对路径） | 改动 |
|---|---|
| `template-source/code/backend/extensions/authorization/docs/auth/sql/ddl.sql` | 本地现行 DDL 原样移动至下行路径 |
| `template-source/code/backend/extensions/authorization/sql/authorization/schema.sql` | 唯一 SQL 源文件 |
| `template-source/code/backend/assembly/src/main/java/com/cmbchina/template/backend/maintenance/BackendAssembler.java` | 追加 sql 资源目录 |
| `template-engine/engine-core/src/main/java/com/devagentstudio/template/engine/source/v3/CodeTemplateReleaseLoader.java` | loadBackend 与根目录校验均允许 sql |
| `scripts/ci/list-code-template-release-inputs.py` | 发布输入纳入 sql |
| `scripts/ci/check-protected-surfaces.py` | DDL 保护锚点移至新路径 |
| `template-source/code/backend/extensions/authorization/src/main/java/com/cmbchina/backend/auth/bootstrap/AuthorizationBootstrapCommand.java` | 默认 DDL 使用新路径 |
| `template-source/code/template-revision.txt` | 增加 Revision |
| `template-engine/engine-core/src/test/java/com/devagentstudio/template/engine/core/v3/CodeTemplateReleaseLoaderTest.java` | 源 SQL/Release/生成文件字节相等及 Base 排除检查 |
| `template-engine/engine-core/src/test/java/com/devagentstudio/template/engine/core/v3/V3UpdatePlannerTest.java` | 旧工程不升级，以及 SQL 冲突、符号链接、非法目录、二进制、非 UTF-8 检查 |
| `template-engine/engine-service/src/test/java/com/devagentstudio/template/engine/service/EngineServiceIT.java` | 新路径、installedArtifacts、双安装路径 payload、重复安装 204、Revision 期望 |
| `template-source/code/backend/assembly/src/test/java/com/cmbchina/template/backend/maintenance/SqlAssetSyncTest.java` | 四种真实 Profile、SQL owner、status/sync、Base/Login 源文件不变 |
| `scripts/ci/test_code_template_release_inputs.py` | SQL 输入、安全检查与真实 Git Revision 回归 |
| `scripts/ci/authorization-bootstrap/src/test/java/com/cmbchina/backend/auth/bootstrap/BootstrapContractTest.java` | 默认路径，以及新文件缺失/无效时不回退旧路径 |
| `scripts/ci/verify-authorization-live-jar.py` | 真实 JAR SQL 字节对称性、旧路径排除、State 与 operation 输出 |
| `template-source/code/backend/extensions/authorization/docs/authorization.md` | 专属 SQL 资产及非自动迁移说明 |
| `template-source/code/backend/base/README.md` | 更新启用后交付路径 |
| `template-source/code/backend/docs/development-guide.md` | 更新后端入口说明 |
| `docs/DEVELOPMENT_GUIDE.md` | 更新当前路径及历史归一记录 |
| 本文档 | 验收证据与限制 |

双平台脚本未修改：两者均先切换至 backend，再使用 Java 默认 DDL 路径；原有参数传输和退出码传播保留。未修改 V3ProjectMaterializer、V3UpdatePlanner 的生产实现或 HTTP/JSON 协议。

## DDL 与 ZIP 证据

以下内容逐字节相等，SHA-256 均为：

```text
dfe39a4dea4d53ee15d8a15068055cf179ee2e34fc6f4b025aa6bab0b9382620
```

- HEAD 中原始 `extensions/authorization/migrations/001-schema.sql`。
- 起始本地 `extensions/authorization/docs/auth/sql/ddl.sql`。
- 当前 `extensions/authorization/sql/authorization/schema.sql`。
- Authorization/Full Generate ZIP 中 `backend/sql/authorization/schema.sql`。
- Base→Authorization、Login→Authorization V4 ZIP 中对应 SQL payload。

真实 JAR 的 Authorization/Full Generate 均包含：

```text
backend/scripts/bootstrap-authorization.sh
backend/scripts/bootstrap-authorization.ps1
backend/sql/authorization/schema.sql
backend/src/main/java/com/cmbchina/backend/auth/bootstrap/AuthorizationBootstrapCommand.java
backend/src/main/java/com/cmbchina/backend/auth/bootstrap/BootstrapDataSource.java
backend/src/main/java/com/cmbchina/backend/common/config/WebMvcConfiguration.java
```

Base/Login 均不含以上资产。四种 Profile 均不含两个旧权限 SQL 路径；重复 Generate 的 ZIP 字节一致且 entry 排序固定。

两条 V4 安装路径均产生下述 SQL operation，Base 路径 index 为 4，Login 路径 index 为 3：

```json
{
  "schemaVersion": 1,
  "operationId": "add-file:backend:authorization:sql/authorization/schema.sql",
  "type": "ADD_FILE",
  "target": "backend/sql/authorization/schema.sql",
  "parameters": {},
  "payloadRef": "payload/backend/sql/authorization/schema.sql"
}
```

两者的 `nextTemplateState.installedArtifacts` 均包含 `backend:authorization:sql/authorization/schema.sql`；保留 ENSURE_CAPABILITY_CONFIG（Base 3 条、Login 2 条），重复 APPLY 均为 HTTP 204。

## 实际验证

| 命令/场景 | 结果 |
|---|---|
| `mvn -f template-engine/pom.xml -pl engine-service -am package` | 通过 |
| `mvn -f template-engine/pom.xml verify` | 通过：Core 9、Service 15，共 24 项 |
| `mvn -f template-source/code/backend/assembly/pom.xml test` | 通过：10 项，包含真实四 Profile 与 SQL status/sync |
| `mvn -f scripts/ci/authorization-bootstrap/pom.xml test` | 5 项通过，1 项真实数据库测试因未配置隔离 TDSQL 而跳过 |
| `python3 -m unittest discover -s scripts/ci -p 'test_*.py'` | 12 项通过，1 项 PowerShell 因本机无 PowerShell 而跳过 |
| `python3 scripts/ci/check-protected-surfaces.py` | 通过 |
| `./scripts/ci/verify-code-template-release-revision.sh HEAD` | 通过 |
| `git diff --check` | 通过 |
| 临时后端副本中 `./backend-template reset authorization` | 通过；未重置原工作区 |
| 临时后端副本中 `./backend-template verify-update authorization` | 未通过：私有父 POM `ZA21:bee-starter-parent:6.0.7` 不在当前可用 Maven 仓库，Authorization 构建即停止，Full 构建未执行 |

真实 JAR 使用 `application-local.yml`、绝对 `TEMPLATE_ENGINE_SOURCE_ROOT` 和 Loopback：

```sh
TEMPLATE_ENGINE_SOURCE_ROOT=/Users/zhangrongrong/Documents/workspace/springboot-template/template-source \
TEMPLATE_ENGINE_PORT=18082 \
java -jar template-engine/engine-service/target/engine-service-1.0.0-SNAPSHOT.jar \
  --spring.config.additional-location=file:/Users/zhangrongrong/Documents/workspace/springboot-template/template-engine/engine-service/config/application-local.yml
python3 scripts/ci/verify-authorization-live-jar.py \
  --url http://127.0.0.1:18082 --output /tmp/authorization-sql-generated
```

验收通过，测试 JAR 已停止。生成工程 backend 目录中的 Java Bootstrap `--dry-run` 另经独立测试 harness 的真实 Runtime Source 编译类执行，加载新 DDL 时退出 0；放置有效旧路径副本、删除新文件时退出 2；新文件无效时退出 2。此检查使用测试 TechnicalPlan/Application 输入，不连接数据库，也不代替生成工程的 Bee Maven 构建。

本机临时日志：`/tmp/authorization-sql-engine-package.log`、`/tmp/authorization-sql-engine-verify.log`、`/tmp/authorization-sql-assembly.log`、`/tmp/authorization-sql-bootstrap.log`、`/tmp/authorization-sql-live-acceptance.log`、`/tmp/authorization-sql-generated-bootstrap.log`、`/tmp/authorization-sql-verify-update.log`。

结论：SQL 源文件、装载、投影、首次更新包及 Bootstrap 默认定位已归一；生成工程完整 Bee 构建、Windows PowerShell 执行和真实 TDSQL 初始化仍需相应私库及环境，不能视为已通过。
