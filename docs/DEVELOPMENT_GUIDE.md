# Template Engine 项目与双端模板开发指南

> 适用仓库：`Hupy2118/springboot-template`；基线：`template_refactor`（核对提交 `60a1758`）。
>
> 本文是模板**维护者**的统一开发文档，按当前 V3 代码描述实际行为。接口的机器可读约束以 OpenAPI / JSON Schema 为准，源码与测试是最终实现依据。历史 V2、Strategy/Patch 和阶段性迁移方案不再作为开发依据。

## 1. 项目定位与架构

本项目提供 **V3 Template Engine + 前后端 Base/Extension 模板**，为 XCodeAgent / DevAgent Studio 提供工程初始生成和增量能力装配。

- **Template Engine**：无状态计算服务，装载模板源码、解析 Capability 依赖并输出确定性的 ZIP 或 Update Package；不保存项目、Workspace、TemplateState，也不执行用户工程的变更、构建或回滚。
- **Base**：各应用共享的前端/后端基础工程，不包含 Login、Authorization 等可选业务能力。
- **Extension**：独立拥有的能力源码及声明；`login`、`authorization` 是现有实现，Authorization 依赖 Login。
- **Assembly / Workspace**：模板开发时用于组装、调试和回写的维护工具链；不是 Engine 的 Runtime Source。用户最终拿到的是普通 React/Spring Boot 工程，而不是需要理解 Extension 的运行时插件系统。

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
└── docs/DEVELOPMENT_GUIDE.md              # 唯一模板维护开发指南
```

### 1.1 源码所有权与发布边界

| 内容 | 是否进入 Runtime Release | 维护规则 |
| --- | --- | --- |
| `code/contracts/` | 是，投放到 `.devagentstudio/template-contracts/` | 对接协议；按版本维护 |
| 双端 `base/` | 是 | 所有 Profile 的通用源码 |
| 双端 `extensions/<id>/extension.yaml`、`src/`、后端 `migrations/` 与能力文档 | 是（仅所选能力的产物进入工程） | Extension 独占源码，Manifest 为声明真相 |
| `code/template-revision.txt` | Release 元数据 | Runtime Source 变更按 Revision Gate 管理 |
| 双端 `assembly/`、`workspace/`、Profile、维护脚本 | 否 | 仅服务模板开发、回写和验证 |
| `template-engine/` | 引擎自身 | 不属于交付给用户的前后端源码 |

**约束**：同一路径只允许一个 Base 或 Extension Owner，不能通过复制顺序覆盖冲突；不要直接把 Assembly 生成的 Registry 反写到 Base；不要恢复旧的 Strategy Registry / Patch / Anchor 体系。

## 2. 开发环境与常用入口

| 对象 | 要求 | 入口 |
| --- | --- | --- |
| V3 Engine | JDK 11+、Maven 3.9+；编译目标 Java 8 | `mvn -f template-engine/pom.xml ...` |
| 前端模板 | Node `>=20.19 <23`、pnpm `11.9.0`（以 package.json 为准） | `cd template-source/code/frontend` |
| 后端模板 | JDK 和仓库自带 Maven Wrapper；维护工具不依赖 Node | `cd template-source/code/backend`，`./backend-template` |

前端 `base/package.json` 维护应用运行依赖与 `dev/build/test`；`frontend/package.json` 管理模板 Profile。后端 `base/pom.xml` 维护基础依赖，Extension Manifest 可声明额外 Maven 坐标。

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

- `backend/base/`：Spring Boot 2.7/Java 8 工程，包含 `Application.java`、`pom.xml`、`application.yml`、`common/config`、`common/exception`、`common/page`、`common/response`。
- `backend/extensions/login/`：身份上下文、Mock Login、拦截器及配置；`LoginWebMvcConfiguration` 注册顺序为 `100`，排除 `/api/login/mock`。
- `backend/extensions/authorization/`：资源、角色、成员、权限 API，Application Service、Domain、Repository、MyBatis、DDL 和 Bootstrap；`AuthorizationWebMvcConfiguration` 注册顺序为 `200`，排除 `/api/login/mock` 和 `/actuator/**`。
- `backend/assembly/`：Java/Maven 维护工具，负责文件合并、注解/Maven Contribution 投影、Status、Sync 和验证。

业务代码推荐按 `adapter/web` → `application` → `domain` / `infrastructure` 的边界放置。Controller 仅做协议适配，统一复用 `ResponseEntity`、`PageParam` / `PageResult`、`BaseExceptionHandler`，数据访问通过 Domain Repository 抽象。

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

1. 复制或新增 `extensions/<id>/src/`；如需数据库资产放 `migrations/`，能力说明放其 `docs/`。保持 `extension.yaml`、源码、DDL 由同一能力维护。
2. 在 YAML Manifest 中声明 `id`、`requires` 和可选 `applicationAnnotations`、`mavenDependencies`；两端同 ID 的 `requires` 应一致。
3. 启动类注解只能声明全限定类名，由 Assembly/Engine 向 `Application.java` 投影；依赖只能通过 Manifest 的 Maven 坐标投影到 `pom.xml`。**不要手工修改 Workspace 中生成的注解/依赖并期望 Sync 反向维护 Manifest**。
4. 单个物理注解或 Maven 依赖可以由多个能力共同声明；引擎按物理资源聚合 Owners，不能按某一个 Extension 的声明去重后错误删除仍被其他能力使用的依赖。
5. 使用 `verify-update <profile>` 验证本 Profile 及全部下游 Profile；再用 V3 Generate/Update 校验交付的完整工程。

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

**Authorization Bootstrap 与规划契约**：`AuthorizationBootstrapCommand` 读取已确认的 TechnicalPlan（`authorization_manifest.schema_version=authorization-manifest.v2`）及 `.devagentstudio/application.json` 中的初始管理员列表。当前代码默认 DDL 路径为 `backend/docs/auth/sql/ddl.sql`，而 Extension Release 中的源码 DDL 路径为 `backend/migrations/001-schema.sql`；在完成路径统一前，应**显式使用 `--ddl=` 指定生成工程中存在的 DDL**，并测试 `--dry-run`。该差异是现存兼容问题，不应被文档掩盖。

## 5. Template Engine 与外部工作流

### 5.1 服务边界与权威契约

| 项目 | 实现 / 权威位置 |
| --- | --- |
| 生成 | `POST /v1/generate`；返回 `application/zip` |
| 更新 | `POST /v1/update`；返回更新包 ZIP 或 `204` 无变化 |
| HTTP 契约 | `template-engine/engine-service/src/main/resources/openapi/engine-service-v1.yaml` |
| ZIP 内部 Schema | `template-engine/engine-service/src/main/resources/contracts/v3/*.schema.json` |
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

Generate ZIP 包含 `frontend/`、`backend/`、`.devagentstudio/template-state.json` 及 Route Projector Contracts。Update 需要提交**完整** `currentTemplateState`、`requestedConfig`、`protocolVersion: "3"` 与 `mode: "APPLY" | "RECONCILE"`。省略 Capability 等同禁用；当前 V3 不支持删除已启用的 Capability。`RECONCILE` 仅用于状态不变时修复受管 Registry / Contribution，不等于刷新所有 Base 源文件。不要假定升级 Revision 会自动覆盖已有用户工程的全部 Base/Contract 文件。

### 5.2 编译与启动 Engine

在仓库根目录：

```bash
mvn -f template-engine/pom.xml -pl engine-service -am package

export TEMPLATE_ENGINE_SOURCE_ROOT="$(cd template-source && pwd)"
java -jar template-engine/engine-service/target/engine-service-1.0.0-SNAPSHOT.jar \
  --spring.config.additional-location="file:$(pwd)/template-engine/engine-service/config/application-local.yml"
```

本地服务监听 `127.0.0.1:18080`（由 `application-local.yml` 控制），仅面向本机。`TEMPLATE_ENGINE_SOURCE_ROOT` 必须为绝对路径；服务无应用层认证，不应直接暴露公网。应用生成后的 Workspace Apply、构建、回滚、State 持久化属于 XCodeAgent / 调用方职责。

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
| Route Projector | 在 `frontend/base` 下运行 `pnpm test:route-projector` |
| Source Path Plugin | 在 `frontend/base` 下运行 `pnpm test:source-path` |
| 任何 Runtime Source 发布 | `./scripts/ci/verify-code-template-release-revision.sh <base-ref>` |

`mvn -f template-engine/pom.xml verify` 包含 Source Boundary、Assembly Parity、Schema 及 Package Executor 等现有测试。前端发布前也可在 `frontend/` 下运行 `pnpm materialize:full`、`pnpm verify:assembly` 和 `pnpm verify:release`。所有修改最后执行 `git diff --check`。

### 6.2 Release 操作规则

1. 修改双端 Base 或 Extension、Manifest、Route Contract 等 Runtime Source；通过相应 Profile 的开发和回写工具落到真实 Source Owner。
2. 运行受影响 Profile 验证及 Engine 集成测试；确认前后端生成产物与维护组装结果一致。
3. 若 Runtime Release Inputs 发生增加、修改或删除，更新 `template-source/code/template-revision.txt` 并通过 Revision Gate。Maintenance-only 工具或本指南变化一般不需要调整 Revision。
4. 检查 `/v1/generate` 的 ZIP 路径、`.devagentstudio/template-state.json`、五个前端 Registry、Route Projector Descriptor，以及选中能力的后端文件。
5. 对 `/v1/update` 做已有项目更新验证；不得假设 Template Engine 自动管理 Workspace 或冲突回滚。

## 7. 关键文件保护与常见误区

| 保护面 | 当前关键文件 / 约束 |
| --- | --- |
| 页面投影 | `code/contracts/route-projector*.json`、`frontend/base/scripts/devagentstudio/route-projector.mjs`、`frontend/base/src/constants/routes.tsx` 的 Marker |
| 页面发现 | `frontend/base/src/utils/pageIdentity.ts`、`pageIdentity.vectors.json`、`pageRouteTree.ts`、`pageRoutes.tsx`；保持 Rsbuild 上下文 Key 与映射一致 |
| 可视化源码定位 | `frontend/base/devtools/elementInspector.ts`、`scripts/sourcePathPlugin.mjs`、`sourcePathLoader.cjs`、`src/index.tsx` 的开发态初始化 |
| 前端 Extension | Loader/Materializer 中五个 Registry 固定目标以及 Base 中对它们的引用 |
| 后端 Contribution | `backend/base/.../Application.java`、`backend/base/pom.xml`、相关 `extension.yaml` |
| 权限初始化 | Authorization 的 `AuthorizationBootstrapCommand.java`、`migrations/001-schema.sql` 和对应规划输入 |
| 增量更新 | `code/template-revision.txt`、V3 Schema、生成的 `.devagentstudio/template-state.json` |

以下属于**当前已知的检查点**，不要把它们写成“已解决”：

- 页面模块上下文 Key 在不同打包路径下可能与 `moduleImportPath` 不一致；页面投影测试应包含实际加载检查。
- 前端 Base 的 `AGENTS.md` 引用了不存在的前端 `docs/project-structure.md`；如需补齐，必须与生成工程中 Agent 指引同步修改。
- 部分旧模块 README / 能力说明中提及 `CapabilityWebMvcConfiguration`；当前后端实际由 `LoginWebMvcConfiguration` 与 `AuthorizationWebMvcConfiguration` 注册拦截器。
- 不要把 `backend/docs/development-guide.md` 中旧的“Engine 尚消费 Legacy 模板”结论继续传播；V3 当前只消费 `template-source/code/`。

## 8. 文档维护原则

**模板维护开发文档仅维护本文件** `docs/DEVELOPMENT_GUIDE.md`，更新规则、命令或架构时直接修改本文，不再新增“改造方案/实施计划/修复记录”到 `docs/`。

根 `README.md` 只保留项目入口；根 `AGENTS.md` 保留 Agent 的代码约束。保留 `template-source/code/backend/base/docs/project-structure.md`、Extension 下的 `docs/login.md`、`docs/authorization.md` 等**随生成工程投放的运行时能力指引**：它们与模板维护过程文档不是同一类资产，删除会改变用户工程输出。OpenAPI、JSON Schema、Manifest、测试也不应因文档收敛而删除。
