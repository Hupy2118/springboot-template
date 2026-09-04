# XcodeAgent 可插拔模板工程重构方案

本文是模板工程 V1 的唯一设计与实施基线。接口、状态模型、Capability、Extension Point、ChangeSet、错误码和验收条件的调整，必须先更新本文，再修改实现与测试。

## 1. 目标、范围与工程基线

当前工程基线为 Java 8、Spring Boot 2.7.2、Maven、React 18、Vite 和 pnpm。仓库根没有 Maven Reactor 或 Maven Wrapper，template-engine 尚未形成工程；V1 不创建根 mvnw，不实现 CLI，也不建立第二套本地组合逻辑。

唯一 Core Engine 使用固定 Template Source、当前 TemplateState 和完整 RequestedConfig 计算 Target State，并输出 ChangeSet。Engine Service 是无状态 HTTP Adapter 与 Package Builder，只负责请求校验、认证、调用 Core 和组装首次工程/更新包；Project、Workspace 与 TemplateState 的持久化均由外部调用方负责。

本项目交付范围：

    Template Source
    TemplateSourceLoader
    Engine Core
    Stage2 Apply Fixture
    Engine Service
    OpenAPI

XcodeAgent 只保留外部参考设计。本项目不实现 XcodeAgent Client、FakeXcodeAgent、Pending Apply 文件、Agent Apply 或恢复测试；它们不属于本项目 V1 退出条件。

| 层 | 负责 | 不负责 |
|---|---|---|
| Template Source | Base、Capability、Schema、Extension、Migration、模板文件 | Project State、HTTP、Workspace |
| TemplateSourceLoader | 加载和校验固定 Source | Capability Resolve、持久化 |
| Core Engine | Resolve、Renderer、Target、Diff、ChangeSet | HTTP、Project Revision、Workspace 写入 |
| Stage2 Fixture | 临时 Workspace Apply、验证、State 写入 | Service 生命周期、Agent 恢复 |
| Engine Service | HTTP、认证、调用 Core、首次工程 ZIP、更新包 ZIP | Project State、Revision、ChangeSet 生命周期、持久化、调用方 Workspace 写入 |
| XcodeAgent 参考设计 | 说明外部执行方协议 | 本项目代码、测试、验收 |

V1 不支持历史 Template Source Snapshot、调用方选择 Source 或版本、内容 Hash/CAS、自动合并人工修改、目录/Glob/二进制 File Operation、任意 XML Patch、Capability 自定义 Config Binding、真实业务数据库 Migration 执行、服务端 Project/ChangeSet 持久化、审批/结果回调、服务端数据库或本项目内的 XcodeAgent 实现。

login 必须可独立启用。authorization 必须以 required 单向依赖 login。Login 不得 import Authorization API、AuthProvider、权限资源、权限页面或权限刷新逻辑。

## 2. Template Source 协议

固定目录：

    template-source/
      template-revision.txt
      catalog.yaml
      base/
        base.yaml
        extension-registry.yaml
        schemas/extensions/
        frontend/
        backend/
      capabilities/<id>/
        capability.yaml
        config.schema.json
        frontend/
        backend/
        migrations/
        docs/

    template-engine/
      pom.xml
      engine-core/
      engine-service/

    validation/
      verify-stage2.sh

### 2.1 Source 注入

template-source/template-revision.txt 是唯一发布代次。

- Stage2 固定从仓库根读取 template-source。
- Stage3 仅从 Service 部署配置读取 Source Root。
- Core 在构造期接收 TemplateSourceContext。
- 业务请求、HTTP 请求和测试参数都不得传入或覆盖 Source Path、Source Ref、Snapshot、Template Version 或 Template Revision。
- Template Source 的任一受管文件、Manifest、Schema、Registry 或 Renderer 协议发生变化时，发布者必须提升 template-revision.txt；同 Revision 的 Source 视为不可变。

### 2.2 Base 与显式文件清单

base.yaml 固定字段：

    schemaVersion: 1
    id: base
    files: []
    reservedRootPaths: ["/", "/page"]
    reservedPagePaths: []
    validationPlan: []

Generated Target 不在 base.yaml 声明，只在 Extension Registry 声明。

files 元素只允许单文件映射：

    source: frontend/src/App.tsx
    target: frontend/src/App.tsx

规则：

- source 相对于所属 Base 或 Capability 根目录；target 为 Workspace 相对 POSIX 文件路径。
- Owner 由所属 Base/Capability 推导，不允许在 File Entry 重复声明。
- base/frontend、base/backend、Capability 的 frontend、backend 下每个非文档普通文件必须在所属 Manifest 的 files 中恰好声明一次。
- Capability 的 migrations 下每个普通文件必须在 migrations 中恰好声明一次，不得同时出现在 files 中；Core 将每个 Migration 物化为一个 Engine-owned Managed File 和对应的 TemplateState.migrations 记录。
- 未声明模板文件报 UNDECLARED_TEMPLATE_FILE；重复 Target 报 FILE_OWNERSHIP_CONFLICT。
- Manifest、Schema、README 等 Source 元数据不列入 files。
- 目录、Glob、绝对路径、..、符号链接、非 UTF-8、二进制、.git、node_modules、target、build、dist、coverage、IDE 文件和 pnpm-lock.yaml 即使被声明也必须拒绝。
- 二进制 favicon 改为 SVG。

### 2.3 Capability Manifest、Config、Dependency 与 Migration

capability.yaml 固定字段：

    schemaVersion
    id
    version
    requires
    provides
    configSchema
    files
    dependencies
    extensions
    migrations

configSchema 只能引用 Capability 根目录的 config.schema.json，不允许内嵌第二份 Schema。

enabled 只属于 Requested Config Envelope。Login 和 Authorization 的 V1 Config Schema 固定为空对象，additionalProperties 为 false。删除未映射到目标工程的 mockLogin Config。

requires 元素：

    id: login
    mode: required

mode 只支持 required、optional、forbidden。V1 不支持 Capability 版本范围。未知 Capability、依赖循环、required 配置缺失、forbidden 组合都在 Resolve 阶段失败。

RequestedConfig 是完整目标配置：

    {
      "capabilities": {
        "login": { "enabled": true, "config": {} },
        "authorization": { "enabled": true, "config": {} }
      }
    }

未声明 Capability 视为未启用；enabled:false 从规范化 Requested Config 删除。Authorization 启用时 Login 自动进入 Effective Config；requiredBy 按 Capability ID 排序。

Dependency 结构：

    dependencies:
      npm:
        - name: ahooks
          version: "^3.8.1"
          section: dependencies
      maven:
        - groupId: example
          artifactId: example
          version: "1.0.0"
          scope: compile
          type: jar
          classifier: ""
          optional: false
          exclusions: []

npm 稳定键为 section,name；Maven 稳定键为 groupId,artifactId,type,classifier。相同键完整值一致时合并 contributors；否则报 DEPENDENCY_CONFLICT。Login 声明 ahooks，Authorization 通过 required Login 获得该依赖。

Migration 仅在 capability.yaml 声明：

    migrations:
      - id: authorization-schema
        source: migrations/001-schema.sql
        target: backend/src/main/resources/xcodeagent/migrations/authorization/001-schema.sql
        order: 100
        mode: COPY

Migration ID 在 Capability 内唯一，Target 全局唯一。Core 只物化 File Operation；删除独立 migration.yaml，不执行数据库 SQL。

## 3. Extension 与 Generated Layer 协议

Registry 唯一位置为 base/extension-registry.yaml。每个 Point 固定字段：

    point
    payloadSchema
    renderer
    target
    cardinality
    orderBy: CONTRIBUTION_ORDER

payloadSchema 引用 base/schemas/extensions/<point>.schema.json。

Contribution Envelope：

    point: frontend.providers
    id: login-provider
    order: 100
    payload: {}

稳定键为 point, capabilityId, id；排序为 order, capabilityId, id。Renderer 不允许按 Capability ID 分支。

| Point | Renderer | Target | Cardinality |
|---|---|---|---|
| frontend.providers | frontend-providers-v1 | frontend/src/generated/capabilityProviders.tsx | MANY |
| frontend.root-routes | frontend-routes-v1 | frontend/src/generated/capabilityRoutes.tsx | MANY |
| frontend.page-routes | frontend-routes-v1 | frontend/src/generated/capabilityRoutes.tsx | MANY |
| frontend.page-wrappers | frontend-routes-v1 | frontend/src/generated/capabilityRoutes.tsx | MANY |
| frontend.menu-hooks | frontend-menus-v1 | frontend/src/generated/capabilityMenus.ts | MANY |
| backend.spring-interceptors | spring-webmvc-v1 | backend/src/main/java/com/cmbchina/backend/common/config/CapabilityWebMvcConfiguration.java | MANY |

Core 每个唯一 renderer,target 只调用 Renderer 一次。frontend-routes-v1 聚合三个路由 Point 后一次性写 capabilityRoutes.tsx；多个 Renderer 不得覆写同一 Generated File。

冲突规则：

- Provider、Page Wrapper、Menu Hook 可多项贡献，按稳定顺序组合。
- Root Route 的 routeId 或绝对 path 重复时报 EXTENSION_CONFLICT。
- Page Route 的 routeId、相对 path 或与 Base reservedPagePaths 冲突时报 EXTENSION_CONFLICT。
- Interceptor 的 className 重复时报 EXTENSION_CONFLICT。

### 3.1 Payload

Provider：

    module: "@/providers"
    export: GlobalContextProvider
    exportType: named
    props: {}

Root Route：

    routeId: login
    path: /login
    index: false
    component:
      module: "@/pages/Login"
      export: default
      exportType: default

Page Route：

    routeId: authorization-management
    name: 权限管理
    path: authorization
    resourceKey: AUTHORIZATION_MANAGEMENT
    component:
      module: "@/pages/System/AuthorizationManagementPage"
      export: default
      exportType: default

Page Wrapper：

    module: "@/components/authorization/wrapAuthorizationPage"
    export: wrapAuthorizationPage
    exportType: named

固定导出签名：

    (element: ReactNode, route: PageRouteDefinition) => ReactNode

Menu Hook：

    module: "@/hooks/useAuthorizationMenuTransform"
    export: useAuthorizationMenuTransform
    exportType: named

固定导出签名：

    (menus: Route[]) => Route[]

Interceptor：

    className: com.cmbchina.backend.auth.common.interceptor.UserWebMvcInterceptor
    order: 100
    pathPatterns: ["/**"]
    excludePathPatterns: []

Payload 校验规则：

- 前端 module 必须以 @/ 开头；exportType=default 时 export 必须为 default，exportType=named 时 export 必须是合法 TypeScript 标识符。
- props 只允许 JSON 对象和值，不允许函数、表达式或原始代码片段。
- Root Route path 必须以 / 开头；Page Route path 必须非空且不以 / 开头。
- 同一 Route 不得同时设置 index=true 和 path。

### 3.2 Generated API 与现有前端衔接

Generated 文件固定导出：

    capabilityProviders.tsx: CapabilityProviders
    capabilityRoutes.tsx: capabilityRootRoutes, capabilityPageRoutes, wrapCapabilityPage
    capabilityMenus.ts: useCapabilityMenus
    CapabilityWebMvcConfiguration.java: CapabilityWebMvcConfiguration

空 Capability 集生成恒等 Provider、空路由、恒等 Wrapper、原样 Menu Hook 和无注册 Interceptor 的配置类。

沿用现有 PageRouteDefinition、createPageRoutes、findFirstPagePath 和 React Router 结构，不重写路由体系。PageRouteDefinition 新增：

    routeId?: string
    component?: React.ComponentType<any>
      | React.LazyExoticComponent<React.ComponentType<any>>
    resourceKey?: string

- exportType=default 生成默认导出 lazy 包装。
- exportType=named 将 m[export] 适配为默认导出后 lazy load。
- createPageRoutes 优先使用 component；Base 原有 pageId/modulePath 继续用于业务页面。
- findFirstPagePath 将带 component 的 Page Route 视为可访问页面。
- wrapCapabilityPage 对 Base 与 Capability 页面按稳定顺序组合，低 order Wrapper 位于外层。
- Layout 对 PAGE_ROUTES + capabilityPageRoutes 生成菜单，并在组件顶层调用 useCapabilityMenus。
- useCapabilityMenus 必须按稳定顺序无条件调用所有 Menu Hook，禁止在循环、条件或回调内调用 Hook。
- Authorization 将 usePageMenus 改为 useAuthorizationMenuTransform(menus)，将 createProtectedRoutes 改为 wrapAuthorizationPage。

### 3.3 Spring Interceptor

生成类固定为 com.cmbchina.backend.common.config.CapabilityWebMvcConfiguration：

- 使用 Configuration 并实现 WebMvcConfigurer。
- Interceptor 类保留 Component，生成类按具体类型构造器注入。
- 贡献渲染为 addInterceptor(...).order(...).addPathPatterns(...).excludePathPatterns(...)。
- 删除 Login 和 Authorization 的既有 WebMvcConfigurer，防止重复注册。
- Login 保留 Capability 私有配置类，只负责 EnableConfigurationProperties(MockLoginProperties.class)；配置前缀改为 xcodeagent.login.mock-login。

## 4. Core、State、Refresh 与 ChangeSet

### 4.1 Core API

    TemplateEngine(TemplateSourceContext sourceContext)

    CorePlanResult plan(
        @Nullable TemplateState currentTemplateState,
        RequestedConfig requestedConfig
    )

确定性输入为 TemplateSourceContext、currentTemplateState、requestedConfig。相同输入必须产生相同 nextTemplateState 和 ChangeSetBody。

    CorePlanResult
      kind: CHANGE | NO_CHANGE
      nextTemplateState
      body: ChangeSetBody | null
      diagnostics

- 首次 Plan 使用 currentTemplateState=null，必返回 CHANGE。
- NO_CHANGE 仅在 Current 非空、规范化 Target State 等于 Current State、Operation 为空时返回，body 为 null。
- V1 删除 STATE_ONLY。

### 4.2 State 与 Target Diff

TemplateState 固定保存：

    templateRevision
    requested
    effective
    capabilities
    managed.files
    managed.nodes
    migrations

不保存 Project Revision、Project ID、ChangeSet ID 或 Service 生命周期。

Managed File 稳定键为 path，Owner 为 BASE、CAPABILITY 或 GENERATED。Managed Node：

    {
      "path": "frontend/package.json",
      "nodeType": "JSON_NODE",
      "key": "/dependencies/ahooks",
      "value": "^3.8.1",
      "contributors": [{ "type": "CAPABILITY", "id": "login" }]
    }

Node 类型只支持 JSON_NODE 与 MAVEN_DEPENDENCY。

同 Source Revision 的 Diff：

- Target 有、Current 无：ADD。
- Target 无、Current 有：DELETE。
- Capability 私有 Full File：Capability 新增时 ADD，移除时 DELETE，持续启用时无 Operation。
- Generated File：贡献集合变化时 UPDATE；Current 不存在时 ADD。
- Node：path、nodeType、key、value、contributors 任一不同即 UPSERT；Target 不存在即 DELETE。

Template Refresh 条件：

    current.templateRevision != source.templateRevision

Refresh 不读取旧 Source Snapshot，固定执行：

- Current 存在、Target 不存在的 Managed File、Node、Migration：DELETE。
- Target 中全部 Capability/Base Full File：Current 存在则 UPDATE，不存在则 ADD。
- Target 中全部 Generated File：Current 存在则 UPDATE，不存在则 ADD。
- Target 中全部 Node：UPSERT。
- Target State 的 templateRevision 更新为 Source Revision。

package.json、pom.xml 可同时是 Base Full File 和 Capability Structured Node 宿主。首次先创建 File 再写 Node；Refresh 先更新 Base File 再写 Target Node。人工修改 Managed File 不受 V1 保护。

### 4.3 ChangeSet Wire Schema

    ChangeSetBody
      requested
      effective
      operations
      validationPlan
      risks

risks 元素固定为 code、severity:LOW|MEDIUM|HIGH、message；diagnostics 元素固定为 code、message、details。二者均按 code、message 稳定排序。

Operation 仅支持：

    ADD_FILE(path, content)
    UPDATE_FILE(path, content)
    DELETE_FILE(path)

    UPSERT_JSON_NODE(path, pointer, value)
    DELETE_JSON_NODE(path, pointer)

    UPSERT_MAVEN_DEPENDENCY(path, key, value)
    DELETE_MAVEN_DEPENDENCY(path, key)

Apply 前置条件：

- ADD 要求路径不存在。
- UPDATE/DELETE 要求路径存在、为普通文件且属于 Current Managed State。
- Node DELETE 仅删除 Current Managed Node。
- JSON Locator 使用 RFC 6901 Pointer。
- Maven Key 为 groupId,artifactId,type,classifier。

排序为删除 Node、删除 File、ADD/UPDATE File、UPSERT Node；同组按规范化路径和稳定键排序。Fixture 不得重排。

JSON 使用 UTF-8、LF、两空格缩进，保留原有键顺序，新键按字典序插入。Maven 使用固定 Maven Model Reader/Writer，Dependency 按稳定键排序。

### 4.4 Preflight 与 Validation

NO_CHANGE 不代表 Workspace 健康。Stage2 Fixture 在 Plan/Apply 前检查 Current State 中所有 Managed File 与结构化宿主仍存在且为普通文件；缺失时报 MANAGED_FILE_MISSING。

validationPlan 元素：

    {
      "id": "backend-test",
      "type": "COMMAND",
      "workingDirectory": "backend",
      "command": ["mvn", "test"],
      "environment": {},
      "timeoutSeconds": 600,
      "blocking": true
    }

命令按参数数组直接执行，不经过 Shell；Working Directory 必须在 Workspace 内；超时、启动失败、非零退出码都阻断 Apply；单命令输出上限为 1 MiB。

pnpm-lock.yaml 由 Workspace 的 pnpm 管理，不进入 Source、Core State 或黄金结果。Base package.json 固定加入：

    {
      "packageManager": "pnpm@11.9.0",
      "engines": { "node": ">=20 <23" }
    }

## 5. 实施与验收

### 阶段一：Source 重构与 Loader

1. 创建 template-engine/pom.xml 与 engine-core 骨架，Java 编译 Target 保持 8。
2. 拆分 Base、Login、Authorization。Login 页面仅通过 frontend/src/apis/login.ts 调用已有 POST /api/login/mock；登录成功仅更新 Login 会话上下文。
3. 外置 Config Schema，迁移 Manifest 到显式 File、Extension、Dependency 和 Migration 协议。
4. 清理构建产物、Source Lockfile、二进制 favicon、旧 Extension、独立 migration.yaml。
5. 实现 TemplateSourceLoader 与 TemplateSourceContractTest。

Contract Test 必测：Revision 缺失、Manifest/Schema 解析、未知字段、非法路径、未声明模板文件、重复 Target、symlink/二进制、未知 Point、Payload、Route/Interceptor 冲突、Dependency 冲突、Migration 引用与 Target。

阶段一只实现 Source 加载与契约校验。

### 阶段二：Core 与真实临时 Workspace

唯一验收入口：

    ./validation/verify-stage2.sh

固定命令：

    mvn -f template-engine/pom.xml \
      -pl engine-core \
      -am \
      -Pstage2-verification \
      verify

工具链为 Maven >= 3.9、Java Runtime >= 11（编译 target=8）、Node >= 20 且 < 23、pnpm 11.9.0。Surefire 执行单测；Failsafe 在该 Profile 执行 Stage2AcceptanceIT。

实现顺序：

    Requested/Effective Config
    → Capability Resolve
    → Target Managed Model
    → Generated Renderer
    → Diff/Refresh
    → ChangeSet
    → Apply Fixture
    → Validation
    → Workspace State 写入与二次 Plan

每场景使用独立临时 Workspace，全部 Blocking Validation 成功后原子写 Workspace 本地状态：

    .xcodeagent/template-state.json

文件内容就是完整 `TemplateState`，不再包裹 Project Revision 或 Service 生命周期字段。

验证顺序为后端 mvn test、前端 pnpm install --no-frozen-lockfile、pnpm test、pnpm build。

验收覆盖：null 首次 Plan、Base/Login/Authorization 组合、二次 NO_CHANGE、Capability 增删、Refresh 全量 UPDATE/ADD/DELETE、六类 Extension 编译、依赖与 Source 冲突、Operation 前置条件、Migration 物化、Validation 失败不写 State、黄金 Core 输出。

阶段二不实现 HTTP、认证、Service Package、XcodeAgent 或真实数据库 Migration。阶段二已经必须完整实现 `CorePlanResult`、`TemplateState` 与全部 ChangeSet Operation；阶段三不得在 Service 层补算或重定义 Core 结果。

### 阶段三：无状态 Engine Service

阶段三只把阶段二已经验收通过的 Core 暴露为无状态 HTTP 服务，并提供首次工程 ZIP 与更新包 ZIP。Service 不拥有 Project，不保存 TemplateState，不保存 ChangeSet，不管理 Apply 生命周期。

固定模块：

    template-engine/
      pom.xml
      engine-core/
      engine-service/
        src/main/resources/openapi/engine-service-v1.yaml

`engine-service` 不引入 Spring JDBC、JPA、MyBatis、Flyway、H2、MySQL、Redis 或其他持久化/缓存组件。V1 不创建数据库 Schema，也没有 Repository/DAO。

#### 5.3.1 Service 边界

Service 只负责：

- 从部署配置加载固定 Source Root，并在启动时构造唯一 `TemplateSourceContext` 与 `TemplateEngine`。
- 校验 HTTP 请求与认证 Scope。
- 将 `currentTemplateState + requestedConfig` 原样映射到 Core 输入。
- 将 `CorePlanResult` 原样映射为 Plan JSON 响应。
- 首次生成时，将 `currentTemplateState=null` 的 CHANGE 结果在内存或 Service 私有临时目录中物化为完整工程 ZIP。
- 更新时，将 Core ChangeSet、`nextTemplateState` 与文件 Payload 组装为 Update Package ZIP。
- 请求结束后删除 Service 私有临时文件；不保留项目级状态、历史或结果。

Service 明确不负责：

- 不识别或保存 `projectId`、Project Revision、Workspace Revision。
- 不保存 `TemplateState`、`ChangeSetBody`、Diagnostics、请求或响应。
- 不维护 PLANNED/APPROVED/APPLIED/FAILED/SUPERSEDED 等状态机。
- 不提供 Approve、Result callback、ChangeSet 查询或恢复接口。
- 不使用服务端幂等记录；相同确定性输入允许直接重新计算。
- 不写调用方 Workspace，不执行 XcodeAgent 的 Workspace Lock、Apply、恢复或本地 State 推进。
- 不执行 `validationPlan` 中的构建/测试命令；Validation 由外部 Apply 方在真实 Workspace 上执行。
- 不执行真实业务数据库 Migration。

#### 5.3.2 HTTP 契约

固定接口：

    POST /v1/plan
    POST /v1/generate
    POST /v1/update

OpenAPI 文件 `engine-service-v1.yaml` 是 HTTP 请求、响应、Content-Type、错误 Envelope、错误码与状态码映射的唯一权威；Controller DTO 不得脱离 OpenAPI 自行扩展字段。

`POST /v1/plan` 请求：

    {
      "currentTemplateState": null | TemplateState,
      "requestedConfig": RequestedConfig
    }

响应固定映射 `CorePlanResult`：

    {
      "kind": "CHANGE" | "NO_CHANGE",
      "nextTemplateState": {},
      "body": null | ChangeSetBody,
      "diagnostics": []
    }

`nextTemplateState.templateRevision` 必须等于本次请求实际使用的 `TemplateSourceContext.templateRevision`。

`POST /v1/generate` 请求只允许：

    {
      "requestedConfig": RequestedConfig
    }

Service 必须等价调用：

    core.plan(null, requestedConfig)

首次 Plan 按 Core 契约必须返回 CHANGE。Service 将 ChangeSet 物化为完整工程 ZIP，并在 ZIP 中写入：

    <workspace files...>
    .xcodeagent/template-state.json

`.xcodeagent/template-state.json` 必须与 `CorePlanResult.nextTemplateState` 语义完全一致。Service 可以使用内存文件映射或请求级临时目录组装 ZIP，但该目录不是 Project Workspace，请求完成后必须删除。

`POST /v1/update` 请求：

    {
      "currentTemplateState": TemplateState,
      "requestedConfig": RequestedConfig
    }

Service 必须等价调用：

    core.plan(currentTemplateState, requestedConfig)

当 Core 返回 CHANGE 时返回 `application/zip` Update Package；当 Core 返回 NO_CHANGE 时返回 `204 No Content`，不得创建空 ChangeSet 或服务端记录。

Update Package 固定结构：

    change-set.json
    next-template-state.json
    payload/
      <ADD_FILE / UPDATE_FILE 对应的 target path>

规则：

- `change-set.json` 必须是 Core `ChangeSetBody` 的规范序列化，不得修改 Operation 顺序。
- `next-template-state.json` 必须是 Core `nextTemplateState` 的规范序列化。
- `payload/` 只物化 `ADD_FILE` 与 `UPDATE_FILE` 的 `content`；目录层级保持 Workspace target path。
- Payload 文件字节必须与对应 Operation 的 UTF-8 `content` 一致。
- DELETE 与结构化 Node Operation 只由 `change-set.json` 描述，不在 Payload 中制造伪文件。
- Update Package 不代表已 Apply；只有调用方在真实 Workspace Apply 和 Validation 成功后，才能用 `next-template-state.json` 覆盖本地 State。

#### 5.3.3 无状态与重试语义

Service 不接收 `Idempotency-Key`，也不保存 requestHash 或 responseBody。

确定性保证来自 Core：相同 `TemplateSourceContext + currentTemplateState + requestedConfig` 必须得到相同 `CorePlanResult`。调用方在网络失败后可以提交同一请求重新计算。

V1 不承诺 ZIP 二进制字节完全一致；ZIP entry 顺序、entry path 与文件内容必须确定，entry timestamp 必须固定为同一常量值，确保验收测试可重复。Plan JSON、ChangeSetBody、TemplateState 与 Payload 内容必须确定。

#### 5.3.4 Source Root 与启动契约

Stage3 的 Source Root 只允许来自部署配置：

    xcodeagent:
      template-engine:
        source-root: /opt/xcodeagent/template-source

规则：

- `source-root` 缺失、为空、不可读或不是目录时 Service 启动失败。
- 启动时只加载一次 `TemplateSourceContext`；加载或 Contract 校验失败时 Service 启动失败，不以降级 Source 对外提供请求。
- 运行中的 HTTP 请求不得指定 Source Path、Source Ref、Template Version 或 Template Revision。
- Source 发布后必须提升 `template-revision.txt`；部署新 Source 通过 Service 重启生效。
- 每个 Plan 响应中的 `nextTemplateState`、Generate Package State、Update Package State 都必须携带该次启动加载的 `templateRevision`。

#### 5.3.5 服务认证

认证仍使用部署配置，不使用数据库。固定配置模型：

    xcodeagent:
      template-engine:
        principals:
          - principal-id: xcodeagent
            principal-type: XCODE_AGENT
            token-sha256: <64 lowercase hex>
            scopes:
              - template.plan
              - template.generate
              - template.update

规则：

- Header 固定使用 `Authorization: Bearer <token>`。
- Service 对 Bearer Token 原始 UTF-8 字节计算 SHA-256，并以 lowercase hex 与配置中的 `token-sha256` 比较；不记录明文 Token。
- `principal-id` 与 `token-sha256` 在配置中都必须唯一；重复、非法 digest、未知 principalType 或未知 scope 均导致启动失败。
- 缺失/非法 Authorization、未知 Token 返回 401 `UNAUTHORIZED`。
- Token 有效但缺少接口所需 Scope 返回 403 `FORBIDDEN`。
- `/v1/plan` 需要 `template.plan`；`/v1/generate` 需要 `template.generate`；`/v1/update` 需要 `template.update`。

#### 5.3.6 错误契约

统一错误 Envelope：

    {
      "code": "CONFIG_INVALID",
      "message": "...",
      "details": {},
      "traceId": "..."
    }

Loader/Core 错误至少包括 `TEMPLATE_SOURCE_INVALID`、`UNDECLARED_TEMPLATE_FILE`、`FILE_OWNERSHIP_CONFLICT`、`CONFIG_INVALID`、`CAPABILITY_NOT_FOUND`、`CAPABILITY_DEPENDENCY_CYCLE`、`DEPENDENCY_CONFLICT`、`EXTENSION_CONFLICT`、`MANAGED_FILE_MISSING`。

Service 自有错误只允许协议层错误，例如 `BAD_REQUEST`、`UNAUTHORIZED`、`FORBIDDEN`、`PACKAGE_BUILD_FAILED`、`INTERNAL_ERROR`。不再存在 `PROJECT_NOT_FOUND`、`STATE_REVISION_CONFLICT`、`CHANGESET_NOT_FOUND`、`CHANGESET_STATUS_INVALID`、`CHANGESET_ALREADY_APPROVED`、`CHANGESET_RESULT_CONFLICT`、`IDEMPOTENCY_CONFLICT`。

OpenAPI 必须固定全部错误码与 HTTP 状态映射。Core 错误到 HTTP 的映射只能在一个集中 Adapter 中定义，Controller 不得自行映射。

#### 5.3.7 Stage3 验收

Stage3 集成测试使用 MockMvc 与真实 `engine-core`，不使用 H2/MySQL，不启动数据库。

必须覆盖：

- Service 启动成功、Source Root 缺失、Source 无效时启动失败。
- Token 缺失、未知 Token、Scope 不足和合法 Scope。
- `/v1/plan` 的首次、已有 State、Capability 增删、Refresh、NO_CHANGE；响应必须与直接调用 Core 的结果一致。
- `/v1/generate` 生成完整 ZIP，`.xcodeagent/template-state.json` 等于 Core `nextTemplateState`；解压后的文件结果与 Stage2 首次 Apply Fixture 黄金结果一致。
- `/v1/update` 的 CHANGE Package 中 `change-set.json`、`next-template-state.json` 与 Core 结果一致，Payload 与 File Operation content 一致。
- `/v1/update` 的 NO_CHANGE 返回 204。
- ZIP entry 不允许绝对路径、`..`、symlink 或越界路径，entry 顺序确定且 timestamp 固定。
- 同一请求重复调用得到相同 Plan/State/ChangeSet/Payload，不依赖任何前一次请求留下的服务端状态。
- 请求级临时目录在成功或失败后均被清理。
- Service 不包含 JDBC/DataSource/Flyway/H2/MySQL/Redis 运行依赖，测试启动不需要任何数据库配置。

#### 5.3.8 本地手工验收 Runbook

本节是 **Stage3 实现完成后** 的人工验收步骤。它不是当前阶段可执行的启动说明：在 `engine-service` 模块、OpenAPI 生成代码和 Package Builder 尚未交付前，不能宣称 Service 已可启动。实现 Stage3 时必须同时提交本节引用的 `validation/stage3/application.yml` 与请求 Fixture；否则 Stage3 不可验收。

验收机需要 JDK 11+、Maven 3.9+、`curl`、`jq` 和 `unzip`。所有命令从仓库根目录执行。Java 编译 target 仍可为 8；JDK 11 只是验收运行环境的下限。

**1. 准备固定验收配置并启动。**

实现必须提供 `validation/stage3/application.yml`，内容至少如下；Token 明文只用于本地 Fixture，不得进入生产配置。`stage3-demo-token` 的 SHA-256 为 `f807843a7ee53c652d63a1d2215e104ab2f265ed0d2bea0cf4c64f1486764593`，`stage3-plan-token` 的 SHA-256 为 `3a1af87bdeb7471c0124f17aa27e90ad46bbbee710dc7cad2c3f6dbd2feea646`。

```yaml
server:
  port: 18080
xcodeagent:
  template-engine:
    source-root: ${STAGE3_SOURCE_ROOT}
    principals:
      - principal-id: stage3-full
        principal-type: XCODE_AGENT
        token-sha256: f807843a7ee53c652d63a1d2215e104ab2f265ed0d2bea0cf4c64f1486764593
        scopes: [template.plan, template.generate, template.update]
      - principal-id: stage3-plan-only
        principal-type: XCODE_AGENT
        token-sha256: 3a1af87bdeb7471c0124f17aa27e90ad46bbbee710dc7cad2c3f6dbd2feea646
        scopes: [template.plan]
```

```sh
mvn -f template-engine/pom.xml -pl engine-service -am package
export STAGE3_SOURCE_ROOT="$(pwd)/template-source"
java -jar template-engine/engine-service/target/engine-service-*.jar \
  --spring.config.additional-location="file:$(pwd)/validation/stage3/"
```

进程必须保持运行，且日志不得出现 DataSource、JDBC、Flyway 或数据库连接初始化。`STAGE3_SOURCE_ROOT` 为空、不存在，或指向非法 Source Root 时，进程必须在监听端口前失败退出并报 `TEMPLATE_SOURCE_INVALID`；这三种情况是启动失败验收，不应再继续发送 HTTP 请求。

**2. 准备请求 Fixture。**

实现必须提交下列两个 JSON Fixture（字段名及嵌套层级以最终 `engine-service-v1.yaml` 为唯一依据；本节的 JSON 是该 OpenAPI 必须接受的最小示例）。Fixture 只是为了让人工验收可重复；Service 的 Request Body 始终接收普通 `application/json` 对象，**不接收文件上传，也不感知 JSON 来自文件还是调用方内存**。

`validation/stage3/requested-authorization.json`：

```json
{
  "capabilities": {
    "authorization": { "enabled": true, "config": {} }
  }
}
```

`validation/stage3/requested-login.json`：

```json
{
  "capabilities": {
    "login": { "enabled": true, "config": {} }
  }
}
```

首次 Plan 的普通 JSON 请求参数为：

```json
{
  "currentTemplateState": null,
  "requestedConfig": {
    "capabilities": {
      "authorization": { "enabled": true, "config": {} }
    }
  }
}
```

下列命令只是在本地由 Fixture 组装该 JSON，便于复用，不改变接口契约：

```sh
jq -n --slurpfile requested validation/stage3/requested-authorization.json \
  '{currentTemplateState: null, requestedConfig: $requested[0]}' \
  > /private/tmp/stage3-plan-initial.json
```

**3. 验收首次 Plan 与认证。**

```sh
curl -sS -D /private/tmp/stage3-plan.headers \
  -o /private/tmp/stage3-plan.json \
  -X POST http://127.0.0.1:18080/v1/plan \
  -H 'Authorization: Bearer stage3-demo-token' \
  -H 'Content-Type: application/json' \
  --data '{"currentTemplateState":null,"requestedConfig":{"capabilities":{"authorization":{"enabled":true,"config":{}}}}}'
jq . /private/tmp/stage3-plan.json
```

预期为 HTTP `200` 和 JSON Plan：`kind` 为 `CHANGE`，`body` 非空，`nextTemplateState.templateRevision` 等于当前 `template-source/template-revision.txt`（当前为 `2026.09.04.1`），并且 `nextTemplateState.effective` 同时包含显式请求的 `authorization` 与其自动引入的 `login`。响应不得包含 projectId、changeSetId、状态机状态或其他服务端持久化标识。对相同请求重复执行两次，两个 JSON 的语义内容必须相同。

```sh
curl -sS -o /private/tmp/stage3-unauthorized.json -w '%{http_code}\n' \
  -X POST http://127.0.0.1:18080/v1/plan \
  -H 'Content-Type: application/json' \
  --data '{"currentTemplateState":null,"requestedConfig":{"capabilities":{"authorization":{"enabled":true,"config":{}}}}}'
curl -sS -o /private/tmp/stage3-forbidden.json -w '%{http_code}\n' \
  -X POST http://127.0.0.1:18080/v1/generate \
  -H 'Authorization: Bearer stage3-plan-token' \
  -H 'Content-Type: application/json' \
  --data '{"capabilities":{"authorization":{"enabled":true,"config":{}}}}'
```

前一个命令必须输出 `401`，后一个必须输出 `403`；两个响应均为统一错误 Envelope，`code` 分别为 `UNAUTHORIZED`、`FORBIDDEN`，且不泄露 Token digest 或明文 Token。

**4. 验收首次生成 Package。**

```sh
curl -sS -D /private/tmp/stage3-generate.headers \
  -o /private/tmp/stage3-generate.zip \
  -X POST http://127.0.0.1:18080/v1/generate \
  -H 'Authorization: Bearer stage3-demo-token' \
  -H 'Content-Type: application/json' \
  --data '{"capabilities":{"authorization":{"enabled":true,"config":{}}}}'
unzip -t /private/tmp/stage3-generate.zip
unzip -Z1 /private/tmp/stage3-generate.zip
unzip -p /private/tmp/stage3-generate.zip .xcodeagent/template-state.json \
  > /private/tmp/stage3-generated-state.json
jq . /private/tmp/stage3-generated-state.json
```

预期为 HTTP `200`、`Content-Type: application/zip` 与可通过 `unzip -t` 的 ZIP。ZIP 至少包含 `frontend/`、`backend/` 的生成文件和 `.xcodeagent/template-state.json`；State 必须与步骤 3 Plan 的 `nextTemplateState` 语义相等，且其 effective capability 包含 `authorization` 和 `login`。所有 entry 均为相对普通文件路径，不得包含绝对路径、`..`、symlink 或越界路径。解压得到的内容须等于 Stage2 首次 Apply 的黄金结果。

**5. 验收更新 CHANGE Package。**

```sh
jq -n \
  --slurpfile state /private/tmp/stage3-generated-state.json \
  --slurpfile requested validation/stage3/requested-login.json \
  '{currentTemplateState: $state[0], requestedConfig: $requested[0]}' \
  > /private/tmp/stage3-update-change.json
curl -sS -D /private/tmp/stage3-update.headers \
  -o /private/tmp/stage3-update.zip \
  -X POST http://127.0.0.1:18080/v1/update \
  -H 'Authorization: Bearer stage3-demo-token' \
  -H 'Content-Type: application/json' \
  --data @/private/tmp/stage3-update-change.json
unzip -t /private/tmp/stage3-update.zip
unzip -Z1 /private/tmp/stage3-update.zip
unzip -p /private/tmp/stage3-update.zip change-set.json | jq .
unzip -p /private/tmp/stage3-update.zip next-template-state.json \
  > /private/tmp/stage3-next-state.json
```

预期为 HTTP `200`、`application/zip`，并且 ZIP 根目录恰有 `change-set.json`、`next-template-state.json` 与 `payload/`。`next-template-state.json` 的 effective capability 仅保留 `login`；`change-set.json` 必须含有删除 `authorization` 所有权文件的 `DELETE` Operation，且 DELETE 不得伪造对应的 payload 文件。新增或修改文件的 payload 必须存在，并与其 File Operation content 一致。

**6. 验收 NO_CHANGE、确定性和无状态性。**

```sh
jq -n \
  --slurpfile state /private/tmp/stage3-next-state.json \
  --slurpfile requested validation/stage3/requested-login.json \
  '{currentTemplateState: $state[0], requestedConfig: $requested[0]}' \
  > /private/tmp/stage3-update-no-change.json
curl -sS -D /private/tmp/stage3-no-change.headers -o /private/tmp/stage3-no-change.body \
  -X POST http://127.0.0.1:18080/v1/update \
  -H 'Authorization: Bearer stage3-demo-token' \
  -H 'Content-Type: application/json' \
  --data @/private/tmp/stage3-update-no-change.json
```

预期为 HTTP `204`、空响应体且不产生 ZIP。随后重启 Service，使用步骤 5 **同一份** `currentTemplateState` 与请求体再次调用 `/v1/update`；应仍得到语义相同的 CHANGE Package。对两次 Package 分别比较 entry 列表、`change-set.json`、`next-template-state.json` 和每个 payload 的 SHA-256，结果必须相同；ZIP 的二进制字节相同不是验收条件。该反向验证排除“第一次请求写入内存或数据库、第二次才能成功”的伪无状态实现。

验收结束后停止进程。正常返回、4xx、5xx 之后都不得在 Source Root 或工作目录下留下由 Service 创建的临时工程、ZIP 或状态文件；仅允许本 Runbook 显式写入 `/private/tmp/stage3-*` 的验收产物。

阶段三完成后，Engine Service 是纯计算 Core 的无状态网络入口与 Package Builder，不成为 Project Registry、ChangeSet Registry 或 Workspace Manager。

## 6. XcodeAgent 外部参考设计

本章为非交付设计，不实现、不测试、不计入本项目完成标准。

TemplateState 属于实际 Workspace，而不是 Engine Service。建议外部执行方在工程中保存：

    .xcodeagent/template-state.json

首次生成：

    用户 RequestedConfig
    → POST /v1/generate
    → 解压完整工程 ZIP
    → 获得 .xcodeagent/template-state.json

已有工程更新：

    读取本地 .xcodeagent/template-state.json
    → 用户新的 RequestedConfig
    → POST /v1/update
    → 若 204，则无模板变更
    → 若返回 Update Package，则 Workspace Lock
    → Workspace Preflight
    → 按 change-set.json 原顺序 Apply
    → Blocking Validation
    → 成功后原子覆盖 .xcodeagent/template-state.json
    → 失败时保持原 TemplateState 不变，并显式恢复或丢弃 Workspace 修改

XcodeAgent 可以在自身体系中增加 Pending Apply、Workspace Revision、恢复、审计或任务幂等，但这些均属于调用方能力，不反向进入 Engine Service 协议。

XcodeAgent 不得 Resolve Capability、修改 Operation 顺序、自行选择 Template Source、伪造 `nextTemplateState` 或在 Apply 失败后推进本地 TemplateState。

## 7. V1 完成标准

1. Loader 拒绝旧 Manifest、非法 Source、未声明模板文件和 Extension/Route/Interceptor 冲突，不提供兼容层。
2. Login 可独立生成和构建，不再反向依赖 Authorization。
3. 阶段一通过 TemplateSourceContractTest。
4. 阶段二仅通过 `./validation/verify-stage2.sh` 完成真实临时 Workspace 闭环，并保证 `CorePlanResult`、完整 `TemplateState`、结构化 Operation 与黄金输出全部符合第 4 章协议。
5. 阶段三完成无状态 Service HTTP、Source 启动契约、认证、OpenAPI、首次工程 ZIP 与更新包 ZIP 验收；V1 不引入数据库、服务端 Project/ChangeSet 状态机或持久化幂等。
6. XcodeAgent 只保留外部参考设计；实际 Workspace Apply、Validation 后 State 推进、Pending/恢复与审计不计入本项目 V1 完成范围。
7. 新增 Capability、Point、State 字段、Operation、Package 字段或错误码前，必须先扩展本文、Schema/OpenAPI、Fixture 与验收测试。
