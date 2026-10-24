# 工程协作指引

## 工程目标与边界

本仓库使用 V3 Template Source 模型，实现工程生成、V4 Capability 增量安装，并保留 V3 Update 兼容入口；双端正式源码唯一位于 `template-source/code` 下的 Base + Extension Source。V4 是更新协议版本，不是另一套模板目录或 HTTP 路径。

Engine Service 是无状态计算入口，不是 Project Registry、Workspace Manager 或 ChangeSet 状态机。不得在 Service 中增加数据库、JDBC/JPA/MyBatis、Flyway、Redis、Repository/DAO、Project ID、服务端 TemplateState 或幂等记录。

实际生成工程中的 Workspace Apply、构建/测试、失败恢复和 State 推进属于外部 DevAgentStudio/调用方，不在本仓库 Service 的职责中。

## 目录职责

- `template-source/code/contracts/`：根目录 Route Projector 契约，生成时交付到 `.devagentstudio/template-contracts/`，属于 V3 Runtime Release Inputs。
- `template-source/code/frontend/base/`、`template-source/code/frontend/extensions/`、`template-source/code/backend/base/`、`template-source/code/backend/extensions/`：V3 Runtime Source。以双端 `extension.yaml` 和 `template-source/code/template-revision.txt` 为契约。
- `template-source/code/frontend/assembly/`、`template-source/code/backend/assembly/`、两端 workspace、profiles、maintenance package 与开发文档：V3 Maintenance-only，不属于 Engine Runtime Release Inputs。
- `template-engine/engine-core/`：纯 Java Core；不得引入 Spring MVC、HTTP、持久化或 ZIP 打包依赖。V3 逻辑放在独立 `core/v3`、`source/v3` 包中。
- `template-engine/engine-service/`：Spring HTTP、Core 映射、错误映射和 Package Builder；当前 Local Service 无应用层认证。不得实现业务 Capability 逻辑。
- `devtools/standalone-adapter/`：本地公开依赖与模拟登录适配工具；只在 `.standalone-build/` 构造独立 Runtime Source，不属于正式 Release Inputs，不得将其 Overlay、Stub 或 Mock 默认值回写正式 Base/Extension。
- `scripts/ci/`：可重复使用的 CI Gate 与 Template Source 构建检查；脚本必须自行定位仓库根目录。
- `docs/DEVELOPMENT_GUIDE.md`：项目架构与前后端模板的唯一维护开发指南。协议变更必须同步权威 OpenAPI / JSON Schema、Fixture 及契约测试。

## 修改规则

1. Runtime Source 的文件所有权必须唯一；禁止 symlink、越界路径和二进制模板输入。V3 Runtime Inputs 由 V3 Code Source Gate 枚举。
2. Capability 的 `config.schema.json`、依赖、Extension Contribution 和 Migration 都属于 Template Source 契约，不要在 Service 中按 Capability ID 写条件分支。V3 Extension Contribution 由 Core Loader 聚合，不在 Service 中按 Extension ID 分支。
3. `RequestedConfig` 是完整目标配置；省略 Capability 等同禁用，依赖由 Resolver 补齐。已安装能力不能卸载，V4 也不升级已安装能力；同有效能力集合的 V4 APPLY 返回 204，不刷新 Release 或回填缺失资产。V4 RECONCILE 仅修复五个前端 Registry 和已有注解/Maven Contribution，要求能力集合与 Revision 不变。`TemplateState` 由调用方持有和提交，Service 不保存它。
4. HTTP 契约以 `template-engine/engine-service/src/main/resources/openapi/engine-service-v1.yaml` 为唯一权威。ZIP 内部 JSON 契约以 `template-engine/engine-service/src/main/resources/contracts/v3/*.schema.json` 和 `contracts/v4/*.schema.json` 为权威。Generate 仍交付 V3 TemplateState；V4 通过 `/v1/update` 的 `protocolVersion: "4"` 选择。Controller 不得私自增加字段或改变 Content-Type/状态码。
5. Local Service 固定监听 `127.0.0.1`，以 Loopback 作为网络暴露边界，不实施应用层认证。未来共享或远程部署时，认证应在 HTTP 接入层独立引入；日志中不得记录完整 Request Body 或完整受管文件内容。
6. ZIP entry 必须使用相对安全路径、固定排序和固定 DOS 本地时间 `1980-01-02 00:00:00`，不能改回 epoch 0。V3/V4 payload 以各自 Operation Schema 为准：V4 的 ADD_FILE、REPLACE_MANAGED_FILE、ENSURE_CAPABILITY_CONFIG 有 payload，注解/Maven 操作没有。V4 packageId 随机，不能要求整个更新包重复字节一致。
7. 保持 Java 8 编译兼容；Spring Boot 2.7 运行依赖与 Jackson YAML/SnakeYAML 版本必须保持兼容，不要单独升级其中一个。
8. Generate 的 `applicationConfig` 投影到 `frontend/.env` 和后端 `application-dev.yml`，新 State 不保存认证参数；后续参数由调用方定点修改。V4 requestedConfig 仅含 capabilities，ENSURE_CAPABILITY_CONFIG 仅交付新能力默认片段，不得整文件覆盖用户配置。
9. Authorization Extension 唯一拥有双平台 Bootstrap 脚本、`sql/authorization/schema.sql` 及 Bootstrap Java；后端 Runtime 资产扫描覆盖 `src/docs/migrations/scripts/sql`。脚本由调用方执行，不得在 Service 中执行。正式数据源沿用 Bee/TDSQL；Standalone 的 MySQL/模拟身份只用于本地验证。

## Standalone Adapter 维护规则

- 开关 `TEMPLATE_ENGINE_STANDALONE_ENABLED` 仅由 `devtools/standalone-adapter/run-engine.sh` 解析，默认 false；不是 HTTP 字段，原生 `java -jar` 不解析它。Source Root 必须与所选模式一致，指向 `template-source` 层而非 `code` 层。
- `manifest.yaml`、`release.yaml` 保持 JSON 语法。覆盖目标需匹配上游 SHA，精确补丁需匹配结果 SHA；上游 Runtime 输入、Overlay 或构造逻辑变化时，审核并提升 standaloneRevision，保持 sourceRevision 与正式 Revision 一致。
- 遵守 staging/audit、Revision fingerprint 台账和活动进程锁；不得删锁、删台账或热替换运行中的 Source 来绕过检查。切换模式或版本须停进程后重新启动。
- Mock 仅用于 dev/local 且要求 loopback 绑定；真实 RBAC、SQL 和 Bootstrap 仍需独立验收。不得把占位数据源启动、dry-run 或服务端更新规划报告为真实数据库/Workspace Apply 已通过。

## XCodeAgent 工作流关键面保护

以下内容是 XCodeAgent 工作流的跨端集成点：Route Projector 及其输入/输出契约、Element Inspector 通道、页面身份映射和业务路由受管标记、Authorization Bootstrap、Extension Registry，以及 TemplateState 的生成和 V3/V4 Schema。

- 未经任务中的明确要求，不得删除、重命名或移除这些关键文件、导出函数、协议字符串、受管区域标记或对外 Schema 字段。
- 需要演进关键面时，必须保留兼容入口，或者在同一变更中明确更新受影响的契约、调用方和 `scripts/ci/check-protected-surfaces.py` 锚点清单；不得以删除检查或绕过 CI 的方式完成变更。
- 该保护仅覆盖上述工作流面，不覆盖普通业务页面、业务接口或一般实现细节。普通业务优化不应为此受到限制。

## 验证要求

完成 Engine Service 相关改动后至少执行：

```sh
mvn -f template-engine/pom.xml -pl engine-service -am package
git diff --check
```

涉及 Template Source 或 Core 行为时，同时执行：

```sh
mvn -f template-engine/pom.xml verify
python3 scripts/ci/check-protected-surfaces.py
./scripts/ci/verify-code-template-release-revision.sh <base-ref>
```

涉及 HTTP、ZIP 或启动配置时，使用 `template-engine/engine-service/config/application-local.yml` 进行真实 JAR 启动验收。该配置要求显式传入绝对 `TEMPLATE_ENGINE_SOURCE_ROOT`，并固定监听 `127.0.0.1`；详情见 `README.md` 和 `docs/DEVELOPMENT_GUIDE.md`。

涉及 Standalone Adapter 时，至少执行 `./devtools/standalone-adapter/build.sh test`、`prepare`、`audit`，再按改动范围执行 HTTP smoke、生成工程构建和真实 JAR 检查。`verify-all` 返回 3 表示真实数据库或 Executor 验收不完整，不能报告为全通过。纯维护文档修改执行链接/命令路径核对及 `git diff --check` 即可；修改 Base/Extension 内随工程交付的文档仍须按 Runtime Source 执行 Revision Gate。
