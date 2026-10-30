# Template Engine 项目与双端模板开发指南

> 适用仓库：`Hupy2118/springboot-template`；核对基线：`c0cca5c`（2026-10-23），包含 V4 Capability 安装、权限 Bootstrap 与 Standalone Adapter。
>
> 本文是模板**维护者**的统一开发文档，按当前 V3 Template Source 模型及 V3/V4 更新协议描述实际行为。接口的机器可读约束以 OpenAPI / JSON Schema 为准，源码与测试是最终实现依据。历史 V2、Strategy/Patch 和阶段性迁移方案不再作为开发依据。

## 1. 项目定位与架构

本项目提供 **V3 Template Source 模型的 Template Engine + 前后端 Base/Extension 模板**，为 XCodeAgent / DevAgent Studio 提供工程初始生成和 V4 增量能力安装。V4 复用现有 Source、Core 与 `/v1/update`，没有另一套 V4 模板目录。

- **Template Engine**：无状态计算服务，装载模板源码、解析 Capability 依赖并输出工程 ZIP 或 Update Package；Generate 在相同输入与 Release 下可重复，V4 更新包的 packageId 随机。不保存项目、Workspace、TemplateState，也不执行用户工程的变更、构建或回滚。
- **Base**：各应用共享的前端/后端基础工程，不包含 Login、Authorization 等可选业务能力。
- **Extension**：独立拥有的能力源码及声明；`login`、`authorization` 是现有实现，Authorization 依赖 Login。
- **Assembly / Workspace**：模板开发时用于组装、调试和回写的维护工具链；不是 Engine 的 Runtime Source。用户最终拿到的是普通 React/Spring Boot 工程，而不是需要理解 Extension 的运行时插件系统。
- **Standalone Adapter**：本地开发工具，复制正式 Runtime 输入并施加受校验的 Overlay，在 `.standalone-build/` 生成使用公开依赖、MySQL 和模拟登录的独立 Source；不改变正式 Bee/TDSQL 模板和 HTTP 请求协议。

```text
springboot-template/
├── template-engine/
│   ├── engine-core/                       # V3 Release Loader / Resolver / Materializer / Update Planner
│   └── engine-service/                    # /v1/generate、/v1/update、ZIP 打包与 OpenAPI
├── template-source/code/
│   ├── template-revision.txt               # 唯一 V3 Release Revision
│   ├── contracts/                          # Route Projector 对接契约
│   ├── frontend/
│   │   ├── base/                           # 完整 React 工程；Runtime Source
│   │   ├── extensions/{login,authorization}/# 前端能力；Runtime Source
│   │   ├── assembly/                       # Node 组装、同步、验证；维护工具
│   │   ├── workspace/                      # 临时组装工程，非真实 Source
│   │   └── package.json                    # 模板维护命令
│   └── backend/
│       ├── base/                           # Spring Boot 基础工程；Runtime Source
│       ├── extensions/{login,authorization}/# 后端能力；Runtime Source
│       ├── assembly/                       # Maven/Java 维护工具
│       ├── backend-template[.cmd]          # 后端维护 CLI
│       └── mvnw[.cmd]                      # Maven Wrapper
├── scripts/ci/                             # V3 Release Revision 门禁
├── devtools/standalone-adapter/             # 本地适配工具、Overlay、测试与使用说明
├── .standalone-build/                      # 被 Git 忽略的本地 Source、生成工程与报告
└── docs/DEVELOPMENT_GUIDE.md              # 唯一模板维护开发指南
```

### 1.1 源码所有权与发布边界

| 内容 | 是否进入 Runtime Release | 维护规则 |
| --- | --- | --- |
| `code/contracts/` | 是，投放到 `.devagentstudio/template-contracts/` | 对接协议；按版本维护 |
| 双端 `base/` | 是 | 所有 Profile 的通用源码 |
| 双端 `extensions/<id>/extension.yaml`、`src/`；后端 `docs/migrations/scripts/sql` | 是（仅所选能力的产物进入工程） | Extension 独占源码，Manifest 为声明真相 |
| `code/template-revision.txt` | Release 元数据 | Runtime Source 变更按 Revision Gate 管理 |
| 双端 `assembly/`、`workspace/`、Profile、维护脚本 | 否 | 仅服务模板开发、回写和验证 |
| `template-engine/` | 引擎自身 | 不属于交付给用户的前后端源码 |
| `devtools/standalone-adapter/`、`.standalone-build/` | 否（正式 Release）；后者提供独立本地 Release | 不将公开依赖替换、Stub 或模拟认证回写正式模板 |

**约束**：同一路径只允许一个 Base 或 Extension Owner，不能通过复制顺序覆盖冲突；不要直接把 Assembly 生成的 Registry 反写到 Base；不要恢复旧的 Strategy Registry / Patch / Anchor 体系。

## 2. 开发环境与常用入口

| 对象 | 要求 | 入口 |
| --- | --- | --- |
| V3 Engine | JDK 11+、Maven 3.9+；编译目标 Java 8 | `mvn -f template-engine/pom.xml ...` |
| 前端模板 | Node `>=20.19 <23`、pnpm `11.9.0`（以 package.json 为准） | `cd template-source/code/frontend` |
| 后端模板 | JDK 和仓库自带 Maven Wrapper；维护工具不依赖 Node | `cd template-source/code/backend`，`./backend-template` |
| Standalone Adapter | Python 3.9+、JDK、Maven；macOS/Linux，Windows 使用 Git Bash/WSL | `devtools/standalone-adapter/run-engine.sh`、`build.sh` |

前端 `base/package.json` 维护应用运行依赖与 `dev/build/test`；`frontend/package.json` 管理模板 Profile。后端 `base/pom.xml` 维护基础依赖，Extension Manifest 可声明额外 Maven 坐标。

正式后端 Base 使用 `ZA21:bee-starter-parent:6.0.7` 和 Bee/TDSQL，构建及运行需企业私仓、驱动和配置环境。Standalone 才使用公开 Spring Boot 2.7.2、MyBatis-Plus 3.5.2 与 MySQL Connector/J 8.2.0；本地公开构建不能替代正式企业环境验收。

### 2.1 Profile

| Profile | 生成组合 | 新文件归属 / 回写 |
| --- | --- | --- |
| `base` | Base | Base |
| `login` | Base + Login | Login Extension |
| `authorization` | Base + Login + Authorization | Authorization Extension |
| `full` | Base + Login + Authorization | 仅集成验证，无新文件 editTarget，不执行 Sync |

已有文件按原 Source Owner 回写；新增文件按当前 Profile 的 `editTarget` 回写。修改 Manifest 后须重新组装。`reset` 会丢弃尚未同步的 Workspace 变更，执行前先运行 `status` / `sync`。

## 3. 前端模板开发

### 3.1 源码分工

- `frontend/base/`：完整可组装的 React + Ant Design/Rsbuild 工程，负责应用入口、Layout、路由运行时、菜单、通用 Access/Identity 抽象、API Client、页面加载及开发预览能力。
- `frontend/extensions/login/src/`：登录页面、Provider、API 和 Guard。
- `frontend/extensions/authorization/src/`：权限管理页面、Provider、权限组件、Hooks 和 API。
- `frontend/extensions/<id>/extension.yaml`：能力依赖及 Provider、Root Route、Page Route、Initializer、Error Reporter 的 Contribution 声明。
- `frontend/assembly/`：合并文件并生成 `workspace/src/extensions/` 下的五个 Registry；Registry 不是 Base 的手写源码。

前端生成 Registry 固定为：

```text
src/extensions/providers.ts
src/extensions/rootRoutes.tsx
src/extensions/systemPageRoutes.ts
src/extensions/initializers.ts
src/extensions/errorReporters.ts
```

**不要**将 Extension 专有的页面、API、Provider 或权限处理写入 Base；业务代码只应依赖已暴露的 Base 入口，不应手工修改上述生成 Registry。

### 3.2 Base 开发与更新

在 `template-source/code/frontend` 下：

```bash
pnpm dev:base                  # 组装 base Profile 并启动 workspace 预览
# 另一个终端：修改 workspace/ 下的源码
pnpm status:workspace
pnpm sync:base                 # 将 Base 变更（含新增/删除）回写 base/
pnpm verify:update:base       # 覆盖 base、login、authorization、full 构建
```

`sync:base` 会回写已有 Base 文件及合法新增文件，但不会回写 Assembly 生成的 Registry 和维护 Metadata。`build:<profile>` 会临时组装、构建并恢复原 Workspace；**构建通过不代表已执行 Sync**。

### 3.3 Login / Authorization Extension 开发

```bash
# 在 frontend/ 下执行：登录能力
pnpm dev:login
# 修改 workspace/src/** 或对应 extensions/login/src/**
pnpm status:workspace
pnpm sync:login
pnpm verify:update:login

# 权限能力（与上述步骤分开使用，先处理上一 Profile 的未同步修改）
pnpm dev:authorization
# 修改 workspace/src/** 或对应 extensions/authorization/src/**
pnpm status:workspace
pnpm sync:authorization
pnpm verify:update:authorization
```

`pnpm dev:full`、`pnpm build:full` 用于集成验证；Full 不应 Sync。如要放弃某个 Profile 的未同步更改，明确执行 `pnpm reset:<profile>`，不要把 `workspace/` 当成长期模板源码。

### 3.4 新增前端 Extension

1. 新建 `extensions/<id>/src/`，将能力页面、API、Hook、Provider 等文件按最终工程目录组织；这些能力不应在用户工程中暴露一个额外的 `extensions/<id>` 运行时层。
2. 增加 `extensions/<id>/extension.yaml`（当前前端组装器读取 JSON 格式的 YAML 子集），声明 `id`、`requires` 和五类 `contributes` 数组。
3. Provider / Root Route / Initializer / Error Reporter 声明 `source` 与 `export`；Page Route 声明稳定的 `id/path/label`，如需权限可声明 `resourceKey`。关联源码必须真实存在，且路径只能由一个 Owner 提供。
4. 更新 `assembly/profiles.json`，增加合适的开发 Profile（如需要）；同时检查 Engine Loader 对相应声明的兼容性。
5. 在最小 Profile 开发、Sync，随后执行其下游 Profile 构建，并用 V3 Engine Generate 验证产物。

不要修改 `src/extensions/*` 生成文件以注册 Extension；唯一声明来源是 Manifest。

### 3.5 XCodeAgent 页面与预览对接（保护面）

**Route Projector**：`code/contracts/route-projector.json` 声明 `route-projector.v2`，生成工程后位于 `.devagentstudio/template-contracts/`，执行命令为：

```bash
# 在生成工程根目录执行，输入通过 stdin 提供
node frontend/scripts/devagentstudio/route-projector.mjs apply
```

最小输入示例：

```json
{
  "protocol": "route-projector.v2",
  "pages": [
    { "pageId": "asset_list", "name": "资产列表", "resourceKey": "PAGE.ASSET_LIST" }
  ]
}
```

执行输出包含 `status`、`requestedPageIds`、`appliedPageIds`、`skippedPageIds`。Projector 全量 reconcile `frontend/src/constants/routes.tsx` 中的 `DEVAGENTSTUDIO_BUSINESS_ROUTES_START/END` 受管区域，只注册 `pageId/name/resourceKey` 对应的路由字段，不生成组件 import。页面身份转换保持一致：`asset_list` → `src/pages/AssetList/index.tsx` → `/page/asset-list`；Runtime 通过 Rsbuild/Rspack 上下文模块装载页面。必须保留 Projector、Descriptor/Schema、两个 Marker、`pageIdentity.vectors.json` 以及动态发现机制。

**Element Inspector**：开发模式下 `frontend/base/src/index.tsx` 加载 `devtools/elementInspector.ts`，`scripts/sourcePathPlugin.mjs` / Rsbuild 配置注入 `data-path`，通过 `devagentstudio:element-inspector`、`ready` / `set-active` / `element-selected` 和 `sourceLocation` 与父窗口通信。这组文件和消息字段不是可随意删除的调试代码。Rsbuild 通过 `sourcePathLoader.cjs` 复用既有转换。

**页面/权限入口**：`PAGE_ROUTES` 经 `pageRegistry`、`pageRouteTree` 和 `pageRoutes` 生成业务路由；同一数据经 `usePageMenus` 生成菜单。启用 Authorization 时，`resourceKey` 由 Access Runtime 和扩展 Provider 处理。禁止维护第二套业务菜单事实。

## 4. 后端模板开发

### 4.1 源码分工

- `backend/base/`：Bee/Spring Boot 2.7/Java 8 工程，包含 `Application.java`、`pom.xml`、`application.yml`、`application-dev.yml`、通用配置、异常、分页及响应设施，不包含可选登录/授权业务。
- `backend/extensions/login/`：SSO 回调、用户信息和登录说明；Manifest 贡献 `EnableAuthClient`、`bee-starter-auth` 及 Bee/YHT 配置。认证上下文由 Bee 的 `TokenContextHolder` / `LocalToken` 提供，正式 Extension 不提供 Standalone Mock。
- `backend/extensions/authorization/`：资源、角色、成员、权限 API，Application Service、Domain、Repository、MyBatis、SQL 和 Bootstrap；权限拦截器由能力自有的 `com.cmbchina.backend.common.config.WebMvcConfiguration` 注册。不要沿用旧分支的 MVC 类名或未经 Bee 环境验证的拦截器顺序。
- `backend/assembly/`：Java/Maven 维护工具，负责文件合并、注解/Maven Contribution 投影、Status、Sync 和验证。

业务代码推荐按 `adapter/web` → `application` → `domain` / `infrastructure` 的边界放置。Controller 仅做协议适配，统一复用 Bee 的 `BeeResponseEntity` 与 `WebGlobalExceptionHandler`；分页结果使用 Base 的 `PageResult`（当前 `PageParam` 由 Authorization Extension 提供），数据访问通过 Domain Repository 抽象。

### 4.2 日常维护命令

在 `template-source/code/backend` 下：

```bash
# macOS/Linux；Windows 请用 backend-template.cmd
./backend-template reset base          # 明确重新组装 Base（丢弃未同步修改）
./backend-template dev base            # 在 workspace 运行 Spring Boot
# 另一个终端修改 workspace/ 下的 Base 文件
./backend-template status
./backend-template sync base           # 回写源码
./backend-template verify-update base  # 连同下游 Profile 验证
```

维护 Extension 时使用对应 Profile：

```bash
./backend-template assemble login
./backend-template dev login
./backend-template status
./backend-template sync login
./backend-template verify-update login

# Authorization 维护使用 assemble/dev/sync/verify-update authorization
# 全量集成验证：
./backend-template build full
./backend-template test-maintenance
```

后端 `assemble` 默认保护未同步 Workspace 修改；`reset` 强制重建。`status` 为只读；`sync` 将已有文件写回原 Owner、新文件写回当前 Profile Owner，删除对应源文件。`full` 无新文件编辑目标。

### 4.3 新增/维护后端 Extension

1. 新增 `extensions/<id>/src/`；能力说明放 `docs/`，可执行资产放 `scripts/`，SQL 放 `sql/`（已有 `migrations/` 仍受扫描）。保持 `extension.yaml`、源码、脚本和 SQL 由同一能力维护；Authorization 的既有唯一 DDL 路径为 `sql/authorization/schema.sql`。
2. 在 YAML Manifest 中声明 `id`、`requires` 和可选 `applicationAnnotations`、`mavenDependencies`、`applicationYaml`、`applicationProfileYaml`；两端同 ID 的 `requires` 应一致。后两项分别增量投影到 Base 的 `application.yml` 和 `application-<profile>.yml`，不能以同路径文件覆盖 Base。
3. 启动类注解只能声明全限定类名，由 Assembly/Engine 向 `Application.java` 投影；依赖只能通过 Manifest 的 Maven 坐标投影到 `pom.xml`。**不要手工修改 Workspace 中生成的注解/依赖并期望 Sync 反向维护 Manifest**。
4. 单个物理注解或 Maven 依赖可以由多个能力共同声明；引擎按物理资源聚合 Owners，不能按某一个 Extension 的声明去重后错误删除仍被其他能力使用的依赖。
5. YAML 的已有受管叶子路径会在 Workspace Sync 时写回其原 Extension；在有 editTarget 的 Profile 中新增 YAML 叶子路径会写回该 Extension。Base 的既有 YAML 叶子仍写回 Base。不同 Extension 对同一叶子给出不同值会被拒绝。
6. 使用 `verify-update <profile>` 验证本 Profile 及全部下游 Profile；再用 V3 Generate/Update 校验交付的完整工程。

当前 Login Manifest 示例：

```yaml
id: login
requires: []
contributes:
  applicationAnnotations:
    - annotationClass: com.cmb.bee.auth.client.config.EnableAuthClient
  mavenDependencies:
    - groupId: ZA21
      artifactId: bee-starter-auth
```

**Authorization Bootstrap 与规划契约**：Authorization Extension 唯一拥有 `scripts/bootstrap-authorization.sh`、`.ps1`、`sql/authorization/schema.sql` 和 Bootstrap Java。Assembly、Core Loader、Release 输入清单统一扫描 `src/docs/migrations/scripts/sql`；不恢复 V2 Strategy/Anchor。Base/Login 不交付这些权限专属资产。Generate 与 V4 首次安装通过通用 Artifact 路径交付，脚本不会在 Engine Service 中执行。

在生成工程外部执行 `/bin/bash backend/scripts/bootstrap-authorization.sh --dry-run`，Windows 使用 `powershell.exe -NoProfile -File backend/scripts/bootstrap-authorization.ps1 --dry-run`；去掉 `--dry-run` 才会连接数据库。脚本定位 backend，调用固定版本 Maven exec 插件和显式 MainClass，逐项环境参数传递保留空格、单双引号和反斜杠，并传播进程失败码。不加载 `.env`；不隐式使用宿主 DB_*。Java 仍可直接接受 `--technical-plan=`、`--application=`、`--ddl=`、`--application-yml=`、`--profile=`、`--db-url=`、`--db-user=`。密码只从目标工程 YAML 或其中显式声明的安全环境占位符读取，不通过命令参数传入。

默认规划文件是工作区 `.devagentstudio/plans/technical-plan.json` 与 `.devagentstudio/application.json`。只接受 confirmed 的 `authorization-manifest.v3`，权限开关来自 application.authorization.enabled，不要求 manifest.enabled。`system_admin` 明确映射数据库 `SYSTEM_ADMIN`；唯一系统资源是 `system_authorization_management`。业务默认角色及授权只校验、不落库；系统角色仅新增管理资源授权，不自动拥有业务资源。Bootstrap 只增不改，不会清除数据库中已有的手工授权。

数据源由独立 `BootstrapDataSource` 读取 `bee.persistence.ds1.datasource` 及对应 Profile YAML，使用配置指定的 TDSQL 驱动。占位符复用 Spring PropertyPlaceholderHelper，禁止静默退回 spring.datasource/MySQL。Profile 优先级为 `--profile`、JVM spring.profiles.active、SPRING_PROFILES_ACTIVE、Base 中的声明，最后默认 dev（1016 将 active 写在 dev 文件自身）。可用逗号指定多个 Profile，按顺序覆盖；缺少文件、占位符循环或错误驱动均失败。不会启动 Spring 容器，也不支持任意 Spring ConfigData imports、Profile groups 或远程配置中心；此类部署必须提供显式本地配置。Base application.yml/application-dev.yml 与既有 applicationYaml/applicationProfileYaml 贡献的值保持不变。

权限拦截器的真实类为 `com.cmbchina.backend.common.config.WebMvcConfiguration`，使用 Bee TokenContextHolder/LocalToken。不能复制旧分支 MVC 类。没有 Bee 实际认证链证据时，不臆定其拦截器顺序或盲目添加 order(200)。

验证入口：

```sh
mvn -f scripts/ci/authorization-bootstrap/pom.xml test
python3 -B -m unittest scripts/ci/test_authorization_scripts.py scripts/ci/test_code_template_release_inputs.py
mvn -f template-source/code/backend/assembly/pom.xml test
mvn -f template-engine/pom.xml verify
```

独立 Bootstrap 测试编译真实 Runtime Source，覆盖 v3、默认工作区、Bee 配置和失败保护，不替代 Bee 运行验收。真实数据库测试仅在显式设置 `AUTHORIZATION_BOOTSTRAP_TEST_CONFIG` 后运行：以私有 Maven settings 执行 `mvn -s <settings> -f scripts/ci/authorization-bootstrap/pom.xml -Ptdsql test`，配置文件旁须有 application-test.yml。测试拒绝非空数据库及名称不以 `_bootstrap_test` 结尾的库，建表/运行两次/检查最小授权后清理本测试创建的四表。没有隔离库则跳过，不能报告为通过。权限运行测试 `AuthorizationMvcTest` 随 Extension 交付，依赖真实 Bee 类型与 test-scope mockito-inline；需在具备私有依赖的生成工程执行 `mvn test`。Windows PowerShell 测试需在 Windows CI 上运行；本机没有 PowerShell 时明确跳过。

**回归边界**：当前 Bootstrap/MVC 与交付合同测试不代表旧后端 Controller、Application Service、Mapper 的全部行为已经回归；旧前端 AuthProvider/RouteGuard 等入口已由当前 Provider/Registry/页面机制承接，也不能仅据文件存在宣称与旧版本完全等价。业务权限及数据访问仍需独立行为验收，不能恢复 V2 Registry/Strategy/Anchor 来补齐。

**存量修复边界**：V4 已安装 Capability 不可自动升级，同有效能力集合 APPLY 为 204，RECONCILE 不回填 Artifact。旧工程补资产须另行设计受控 repair：先检查 State 与磁盘、所有权、目标缺失状态及来源 Revision；目标存在或已被用户修改时拒绝覆盖，展示补丁预览；仅允许确定的缺失资产，外部事务更新 StateSequence/回滚日志，禁止手改 State 绕过校验。当前没有 repair API。

## 5. Template Engine 与外部工作流

### 5.1 服务边界与权威契约

| 项目 | 实现 / 权威位置 |
| --- | --- |
| 生成 | `POST /v1/generate`；返回 `application/zip` |
| 更新 | `POST /v1/update`；返回更新包 ZIP 或 `204` 无变化 |
| HTTP 契约 | `template-engine/engine-service/src/main/resources/openapi/engine-service-v1.yaml` |
| ZIP 内部 Schema | `template-engine/engine-service/src/main/resources/contracts/v3/*.schema.json`（旧协议）与 `contracts/v4/*.schema.json`（增量安装） |
| Release 输入 | `template-source/code/` 的 Base、Extension、Contracts、Revision |
| 生成器 | `CodeTemplateReleaseLoader` → `ExtensionResolver` → `V3ProjectMaterializer` |
| 更新器 | `V3UpdatePlanner`；调用方负责安全执行和提交 `nextTemplateState` |

生成调用例子：

```json
{
  "requestedConfig": {
    "capabilities": {
      "authorization": { "enabled": true, "config": {} }
    }
  }
}
```

Generate ZIP 包含 `frontend/`、`backend/`、`.devagentstudio/template-state.json` 及 Route Projector Contracts。Generate 仍输出 `schemaVersion: 3` 的 State，供后续 V4 Update 使用；Update 请求/操作包的 `protocolVersion: "4"` 与 State 的 Schema 版本是两个字段，不要手工将 State 版本改成 4。

首次 Generate 可在 `requestedConfig.applicationConfig` 中提交应用参数，必填 `appName`、`appIcon`、`theme.primaryColor`，可选 `auth` 和 `authorization.api`。`ApplicationConfigProjector` 将应用外观参数写入 `frontend/.env` 的 `PUBLIC_APP_NAME/PUBLIC_APP_ICON/PUBLIC_THEME_PRIMARY_COLOR`；启用 YHT 登录时写入 `PUBLIC_YHT_*` 并覆盖 `backend/src/main/resources/application-dev.yml` 中对应的 `yht.authentication.*` 标量；权限 API 参数写入同文件的 `yht.api.*`。`application.yml` 的 Bee 引用结构和 Profile 默认配置由 Base/Extension Manifest 提供，不能把私钥投影到前端。当前 Generate State 不包含 managedConfiguration 或认证参数；后续参数更新由调用方定点修改 `.env` 与 `application-dev.yml`。

Generate 与 Update 的 ZIP entry 按路径固定排序，时间固定为 ZIP DOS 本地时间 `1980-01-02 00:00:00`，避免时区造成额外差异。不要使用 Unix epoch 0：部分 Maven 资源复制实现会把源文件和不存在的目标均视作时间戳 0，跳过复制后因目标不存在而报错。验收解压工程时也应保留该固定时间，避免用写文件时的当前时间掩盖此类问题。V4 更新包每次生成随机 packageId，因此固定排序/时间不意味着整个 V4 ZIP 字节可重复。

V4 增量安装向 `POST /v1/update` 提交完整 `currentTemplateState`、只含 `capabilities` 的 `requestedConfig`、`protocolVersion: "4"` 与 `mode: "APPLY" | "RECONCILE"`。省略 Capability 等同禁用，依赖由 Resolver 补齐；有效目标配置缺少已安装能力或改变其配置时返回 409，不支持卸载或升级。V4 不接受 applicationConfig，后续参数编辑不经过 Update。

| 行为 | V4 当前约束 |
| --- | --- |
| APPLY 安装 | 为尚未安装的 Extension 添加 Artifact、注解/Maven Contribution，替换五个前端 Registry，并交付能力配置默认片段 |
| 同有效能力集合 APPLY | 返回 204；不会刷新 Release、升级既有文件或补装已安装能力的缺失资产。传入 State 的 Revision 高于服务 Release 时仍拒绝 |
| RECONCILE | 能力集合与服务 Revision 必须不变；仅替换五个前端 Registry 并 Ensure 现有注解/Maven Contribution，不回填 Artifact、YAML 或 `.env` |
| State 推进 | 包的 sourceRevision 来自传入 State；expectedStateSequence 缺省为 0，成功安装的 nextTemplateState.stateSequence 加一；RECONCILE 保持序号 |
| 包元数据 | packageId 随机；payloadManifest 仅含 size，无 V3 的 sha256 与 State digest 字段 |

`ENSURE_CAPABILITY_CONFIG` 的 payload 是新能力默认片段，仅允许 `backend/src/main/resources/application.yml`、`application-dev.yml` 和 `frontend/.env`。外部 Executor 应按 YAML/dotenv 语义补齐缺失配置、保留用户已有值，不能把片段作为整文件替换。后端 Manifest 支持多个 Profile 的 Generate/Assembly 投影，但当前 V4 配置安装仅交付主配置与 dev Profile。ADD_FILE、REPLACE_MANAGED_FILE 及 ENSURE_CAPABILITY_CONFIG 有 payload；注解和 Maven 操作无 payload，以对应 Operation Schema 为准。

调用方须验证 ZIP 路径、大小、条目唯一性和操作白名单，以工作区锁、原始字节比较及恢复事务防止覆盖用户修改；成功 Apply 并完成验证后才持久化 nextTemplateState。Update ZIP 不是完整工程，不能直接解压覆盖工作区。旧 V3 Update 暂保留兼容；新 Capability 增量安装使用 V4，避免旧链路重建 YAML/配置块的覆盖语义。

### 5.2 编译与启动 Engine

在仓库根目录：

```bash
mvn -f template-engine/pom.xml -pl engine-service -am package

export TEMPLATE_ENGINE_SOURCE_ROOT="$(cd template-source && pwd)"

java -jar template-engine/engine-service/target/engine-service-1.0.0-SNAPSHOT.jar \
  --spring.config.additional-location="file:$(pwd)/template-engine/engine-service/config/application-local.yml"
```

本地服务监听 `127.0.0.1:18080`（由 `application-local.yml` 控制），仅面向本机。`TEMPLATE_ENGINE_SOURCE_ROOT` 必须为绝对路径；服务无应用层认证，不应直接暴露公网。应用生成后的 Workspace Apply、构建、回滚、State 持久化属于 XCodeAgent / 调用方职责。

Source Root 指向 `template-source` 层，不是 `template-source/code`；可用 `TEMPLATE_ENGINE_PORT` 覆盖端口。服务没有约定的 `/health`，使用真实 `POST /v1/generate` 验证就绪。请求建议使用 `Accept: application/zip, application/json`：只接受 ZIP 时，当前错误 JSON 存在内容协商限制。完整请求样例见 [Standalone tests/requests](../devtools/standalone-adapter/tests/requests/)，同样适用于正式模式，不包含模式字段。

### 5.3 本地 Standalone Adapter

Standalone 解决无企业私仓环境下的开发验证需求；正式 Bee/TDSQL 与企业 SSO 的运行结果仍需企业环境验证。完整工具说明见 [Adapter README](../devtools/standalone-adapter/README.md)，其下的 reports 是带 Revision 的历史证据，不代表本次文档更新重新执行了验收。

在仓库根目录，先按 5.2 打包 JAR，再执行：

```sh
unset TEMPLATE_ENGINE_SOURCE_ROOT
export TEMPLATE_ENGINE_STANDALONE_ENABLED=true
./devtools/standalone-adapter/run-engine.sh
```

| 模式 | Source Root | Revision 来源 |
| --- | --- | --- |
| native（开关缺省或 false） | `<repo>/template-source` | `code/template-revision.txt`，当前 `2026.10.30.1` |
| standalone（开关 true） | `<repo>/.standalone-build/template-source` | `devtools/standalone-adapter/release.yaml`，当前 `2026.10.30.1001` |

`run-engine.sh` 仅接受 true/false，发现已设置的 Source Root 与模式不一致会拒绝启动。Standalone 会 prepare/audit 后执行同一个 Engine JAR 与 `application-local.yml`；关闭时不执行 Python 或访问 Adapter 清单/临时目录。直接 `java -jar` 不解析此开关，HTTP 也不接受 standalone 字段。切换模式或修订前先停止已有进程，再重启加载 Source 和新 JAR；已下载工程不会自动更新。

**维护适配内容**：`manifest.yaml` 与 `release.yaml` 使用 JSON 语法。覆盖/删除/补丁逐目标校验上游 SHA，新增目标必须不存在；精确片段补丁还校验结果 SHA。构造只复制 Loader 的 Runtime ownership roots，拒绝 symlink、二进制、越界和大小写碰撞；staging 审核通过后替换有效树。变更上游 Runtime 输入、清单、Overlay 或构造逻辑后，更新 sourceRevision（与正式源一致）并审核提升 standaloneRevision；fingerprint 台账阻止同 Revision 对应不同内容。活动服务持有 Release 锁时不得 prepare 或热替换，不能删除锁或台账绕过检查。

```sh
./devtools/standalone-adapter/build.sh test
./devtools/standalone-adapter/build.sh prepare  # 运行中的 Standalone 引擎会阻止重新准备
./devtools/standalone-adapter/build.sh audit
```

**生成后端与模拟登录**：Standalone 后端使用 `spring.datasource`，通过 `DB_URL/DB_USERNAME/DB_PASSWORD` 配置 MySQL，默认 local Profile、`127.0.0.1:8080`（可用 BACKEND_PORT 覆盖）。含 Login 的工程默认开启 Mock，dev/local 下注册且要求 loopback；仅 prod Profile 不注册，也可用 `STANDALONE_MOCK_ENABLED=false` 关闭。默认回调 `GET /api/sso/login?code=mock-code` 返回白名单首位 `member-001 / Local Admin` 并设置 Cookie；code 仅是非空占位参数，不代表企业授权码校验。可用 STANDALONE_MOCK_SSO_USER_ID/STANDALONE_MOCK_USERS 覆盖固定身份/白名单，不能通过请求任意冒充用户。

签名和旧 POST 登录密钥缺省在每次后端启动时独立随机生成，仅保留在内存，重启后旧 Cookie 失效；如使用 `POST /api/standalone/login`，需显式设置至少 32 字符的 STANDALONE_MOCK_LOGIN_SECRET。`GET /api/standalone/me` 读取当前身份，`POST /api/standalone/logout` 清 Cookie。1008 的前端 Overlay 补齐 logoutApi 和本地登出页面：等待服务端成功后清理身份/权限缓存并返回首页。模拟身份不绕过原始 RBAC；系统管理员的实际资源仍由数据库与 Bootstrap 决定。

**验证边界**：HTTP smoke 验证 Generate、ZIP 与 V4 Base→Login→Authorization 规划；完整验证入口如下，需另一个终端中已有 Standalone 服务：

```sh
./devtools/standalone-adapter/build.sh verify-http-only --engine-url http://127.0.0.1:18080
./devtools/standalone-adapter/build.sh verify-full --engine-url http://127.0.0.1:18080 --manual-database
./devtools/standalone-adapter/build.sh verify-all --engine-url http://127.0.0.1:18080 --manual-database
```

自动生成工程验证使用关闭端口和占位凭据，不连接或写入真实 MySQL；Bootstrap 只执行 dry-run 和故障用例。公开依赖构建使用隔离 Maven settings 与 `.standalone-build/m2`。`verify-all` 返回 3 表示集成验收不完整，失败为 1、参数错误为 2；不能把 3 当作全通过。真实数据库、Bootstrap 首次/重复/锁竞争及现有 XCodeAgent Executor Apply 需独立验收；手工数据库助手限制目标为本地 `*_standalone_test` 库，需自行准备隔离空库，操作见 Adapter README。

**回退与来源隔离**：停止 Standalone 引擎后，unset 两个变量并按 5.2 原生命令重启，或使用默认关闭的 run-engine.sh。V4 API 无 Variant 身份字段，独立 Revision 不能严格阻止全部跨模式 Update；调用方必须保留 State 来源，使用同模式工程的完整 State，不能混用 native/standalone State。

## 6. 构建、验证与发布

### 6.1 按改动范围验收

| 改动范围 | 最少检查 |
| --- | --- |
| 前端 Base | `pnpm verify:update:base` |
| 前端 Login | `pnpm verify:update:login` |
| 前端 Authorization | `pnpm verify:update:authorization` |
| 后端 Base | `./backend-template verify-update base` |
| 后端 Login | `./backend-template verify-update login` |
| 后端 Authorization | `./backend-template verify-update authorization` |
| Engine、双端组合与接口 | `mvn -f template-engine/pom.xml verify` |
| Authorization Bootstrap / 资产交付 | `mvn -f scripts/ci/authorization-bootstrap/pom.xml test`；`python3 -B -m unittest scripts/ci/test_authorization_scripts.py scripts/ci/test_code_template_release_inputs.py` |
| Standalone 清单、Overlay、构造工具 | `build.sh test`、`prepare`、`audit`，再按影响执行 5.3 的 HTTP/生成工程验证 |
| Route Projector | 在 `frontend/base` 下运行 `pnpm test:route-projector` |
| Source Path Plugin | 在 `frontend/base` 下运行 `pnpm test:source-path` |
| 工作流保护面 | `python3 scripts/ci/check-protected-surfaces.py` |
| 任何 Runtime Source 发布 | `./scripts/ci/verify-code-template-release-revision.sh <base-ref>` |

`mvn -f template-engine/pom.xml verify` 包含 Source Boundary、Assembly Parity、Schema 及 Package Executor 等现有测试。前端发布前也可在 `frontend/` 下运行 `pnpm materialize:full`、`pnpm verify:assembly` 和 `pnpm verify:release`。所有修改最后执行 `git diff --check`。

Service 改动至少运行 `mvn -f template-engine/pom.xml -pl engine-service -am package`；涉及 HTTP、ZIP 或启动配置时，还须用权威 `application-local.yml` 与绝对 Source Root 做真实 JAR 启动验收。纯维护文档变更核对链接、命令路径和差异即可；Base/Extension 内交付给用户的文档属于 Runtime 输入，仍需 Revision Gate。

### 6.2 Release 操作规则

1. 修改双端 Base 或 Extension、Manifest、Route Contract 等 Runtime Source；通过相应 Profile 的开发和回写工具落到真实 Source Owner。
2. 运行受影响 Profile 验证及 Engine 集成测试；确认前后端生成产物与维护组装结果一致。
3. 若 Runtime Release Inputs 发生增加、修改或删除，更新 `template-source/code/template-revision.txt` 并通过 Revision Gate。Maintenance-only 工具或本指南变化一般不需要调整 Revision。
4. 检查 `/v1/generate` 的 ZIP 路径、`.devagentstudio/template-state.json`、五个前端 Registry、Route Projector Descriptor，以及选中能力的后端文件。
5. 对 `/v1/update` 做已有项目更新验证；不得假设 Template Engine 自动管理 Workspace 或冲突回滚。

Standalone 使用自己的 release.yaml 与 fingerprint 台账；正式 Revision 变化后还须同步其 sourceRevision、审核上游目标 SHA 和 Overlay，并提升 standaloneRevision。不能只改版本号而跳过输入审计，也不能将本地适配源作为正式发布源。

## 7. 关键文件保护与常见误区

| 保护面 | 当前关键文件 / 约束 |
| --- | --- |
| 页面投影 | `code/contracts/route-projector*.json`、`frontend/base/scripts/devagentstudio/route-projector.mjs`、`frontend/base/src/constants/routes.tsx` 的 Marker |
| 页面发现 | `frontend/base/src/utils/pageIdentity.ts`、`pageIdentity.vectors.json`、`pageRouteTree.ts`、`pageRoutes.tsx`；保持 Rsbuild 上下文 Key 与映射一致 |
| 可视化源码定位 | `frontend/base/devtools/elementInspector.ts`、`scripts/sourcePathPlugin.mjs`、`sourcePathLoader.cjs`、`src/index.tsx` 的开发态初始化 |
| 前端 Extension | Loader/Materializer 中五个 Registry 固定目标以及 Base 中对它们的引用 |
| 后端 Contribution | `backend/base/.../Application.java`、`backend/base/pom.xml`、相关 `extension.yaml` |
| 权限初始化 | Authorization 的 `AuthorizationBootstrapCommand.java`、`sql/authorization/schema.sql`、双平台 scripts 和对应规划输入 |
| 增量更新 | `code/template-revision.txt`、V3/V4 Schema、生成的 `.devagentstudio/template-state.json`；保持 State 与更新协议版本的区别 |

以下属于**当前已知的检查点**，不要把它们写成“已解决”：

- 页面模块上下文 Key 在不同打包路径下可能与 `moduleImportPath` 不一致；页面投影测试应包含实际加载检查。
- 前端 Base 的 `AGENTS.md` 引用了不存在的前端 `docs/project-structure.md`；如需补齐，必须与生成工程中 Agent 指引同步修改。
- 后端权限 MVC 入口为 Authorization 自有 `com.cmbchina.backend.common.config.WebMvcConfiguration`；Login 认证由 Bee EnableAuthClient 接入，其实际顺序需要 Bee 环境验证。
- `backend/docs/development-guide.md` 仍有旧 MVC 类名、拦截器顺序和“尚不会改变 Engine 产物”的历史说明；后端 README 链接到该文件时，架构与发布边界应以本指南和当前 Loader 为准，当前只消费 `template-source/code/` 模型。
- Standalone 的构建/启动与模拟登录证据不覆盖企业 SSO、真实数据库授权或 Workspace Executor Apply；历史 reports 中早期结论应结合后续 Revision 的补充记录阅读。

## 8. 文档维护原则

**模板维护开发文档仅维护本文件** `docs/DEVELOPMENT_GUIDE.md`，更新规则、命令或架构时直接修改本文，不再新增“改造方案/实施计划/修复记录”到 `docs/`。

根 `README.md` 保留项目入口及最小启动/请求示例；根 `AGENTS.md` 保留 Agent 的代码约束。`devtools/standalone-adapter/README.md` 作为本地工具使用说明保留，详细覆盖清单与历史验收证据放在其 reports，本指南维护集成边界并链接该入口，不把历史验收结论当作当前自动通过。

保留 `template-source/code/backend/base/docs/project-structure.md`、Extension 下的 `docs/login.md`、`docs/authorization.md` 等**随生成工程投放的运行时能力指引**：它们与模板维护过程文档不是同一类资产，删除会改变用户工程输出。OpenAPI、JSON Schema、Manifest、测试也不应因文档收敛而删除。
