# XcodeAgent 可插拔模板工程重构方案

本文是模板工程 V1 的唯一设计与实施基线。接口、状态模型、Capability、Extension Point、ChangeSet、错误码和验收条件的调整，必须先更新本文，再修改实现与测试。

## 1. 目标、范围与工程基线

当前工程基线为 Java 8、Spring Boot 2.7.2、Maven、React 18、Vite 和 pnpm。仓库根没有 Maven Reactor 或 Maven Wrapper，template-engine 尚未形成工程；V1 不创建根 mvnw，不实现 CLI，也不建立第二套本地组合逻辑。

唯一 Core Engine 使用固定 Template Source、当前 TemplateState 和完整 RequestedConfig 计算 Target State，并输出 ChangeSet。Engine Service 只治理 Project State、Revision、ChangeSet 生命周期、认证和幂等。

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
| Core Engine | Resolve、Renderer、Target、Diff、ChangeSet | HTTP、Revision、Workspace 写入 |
| Stage2 Fixture | 临时 Workspace Apply、验证、State 写入 | Service 生命周期、Agent 恢复 |
| Engine Service | Project State、ChangeSet 生命周期、认证、幂等、审计 | 重写 Core、写 Workspace |
| XcodeAgent 参考设计 | 说明外部执行方协议 | 本项目代码、测试、验收 |

V1 不支持历史 Template Source Snapshot、调用方选择 Source 或版本、内容 Hash/CAS、自动合并人工修改、目录/Glob/二进制 File Operation、任意 XML Patch、Capability 自定义 Config Binding、真实业务数据库 Migration 执行或本项目内的 XcodeAgent 实现。

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

每场景使用独立临时 Workspace，全部 Blocking Validation 成功后原子写：

    { "revision": null, "templateState": {} }

验证顺序为后端 mvn test、前端 pnpm install --no-frozen-lockfile、pnpm test、pnpm build。

验收覆盖：null 首次 Plan、Base/Login/Authorization 组合、二次 NO_CHANGE、Capability 增删、Refresh 全量 UPDATE/ADD/DELETE、六类 Extension 编译、依赖与 Source 冲突、Operation 前置条件、Migration 物化、Validation 失败不写 State、黄金 Core 输出。

阶段二不实现 HTTP、Project Revision、认证、幂等、ChangeSetRecord、XcodeAgent 或真实数据库 Migration。

### 阶段三：极薄 Engine Service

Service 使用 Spring JDBC、MySQL 8、Flyway；集成测试使用 H2 MySQL Mode。

持久化模型：

    ProjectState
      projectId, revision, templateState, lastAppliedChangeSetId

    ChangeSetRecord
      changeSetId, projectId, fromRevision, toRevision,
      nextTemplateState, body, status, principalId, requestHash,
      createdAt, approvedAt?, appliedAt?, failedAt?, resultDiagnostics?

    IdempotencyRecord
      principalId, projectId, operation, key, requestHash, responseBody, createdAt

ChangeSet 状态：

    PLANNED
    APPROVED
    APPLIED
    FAILED
    SUPERSEDED

允许迁移：

    PLANNED  -> APPROVED | SUPERSEDED
    APPROVED -> APPLIED | FAILED
    APPLIED | FAILED | SUPERSEDED -> terminal

HTTP 契约：

    POST /v1/projects/{projectId}/plan
    POST /v1/projects/{projectId}/simulate
    POST /v1/projects/{projectId}/change-sets/{changeSetId}/approve
    GET  /v1/projects/{projectId}/change-sets/{changeSetId}
    POST /v1/projects/{projectId}/change-sets/{changeSetId}/results

Plan/Simulate 请求为 expectedRevision、requestedConfig；Approve 请求为 expectedRevision；Result 请求为 status:SUCCEEDED|FAILED、diagnostics。OpenAPI 是所有 HTTP 请求、响应和错误 Envelope 的唯一权威。

统一错误 Envelope：

    {
      "code": "STATE_REVISION_CONFLICT",
      "message": "...",
      "details": {},
      "traceId": "..."
    }

Loader/Core 错误包括 TEMPLATE_SOURCE_INVALID、UNDECLARED_TEMPLATE_FILE、FILE_OWNERSHIP_CONFLICT、CONFIG_INVALID、CAPABILITY_NOT_FOUND、CAPABILITY_DEPENDENCY_CYCLE、DEPENDENCY_CONFLICT、EXTENSION_CONFLICT、MANAGED_FILE_MISSING。Service 错误包括 PROJECT_NOT_FOUND、STATE_REVISION_CONFLICT、CHANGESET_NOT_FOUND、CHANGESET_STATUS_INVALID、CHANGESET_ALREADY_APPROVED、CHANGESET_RESULT_CONFLICT、IDEMPOTENCY_CONFLICT、FORBIDDEN。OpenAPI 固定这些 code 与 HTTP 状态映射。

Plan 规则：

- Project 不存在且 expectedRevision=null：同一事务内插入 Revision 0 Project、以 current=null 调用 Core、创建首个 PLANNED ChangeSet；任一步失败整体回滚。
- 并发首次 Plan 由 Project 主键约束保护；竞争失败方重读后返回 STATE_REVISION_CONFLICT。
- Project 存在时 expectedRevision 必须等于当前 Revision。
- 存在 APPROVED ChangeSet 时拒绝新 Plan。
- 新 CHANGE Plan：同 Revision 全部 PLANNED 标记 SUPERSEDED，再创建新 PLANNED。
- 新 NO_CHANGE Plan：同 Revision 全部 PLANNED 标记 SUPERSEDED，不创建 ChangeSet，不推进 Revision。
- Simulate 使用已应用 TemplateState；Project 不存在且 expectedRevision=null 时用 null State 计算且不持久化。

Approve 仅允许 PLANNED 到 APPROVED，并验证 expected Revision。一个 Project/Revision 最多一个 APPROVED ChangeSet。

Result 仅允许 APPROVED+SUCCEEDED 到 APPLIED 或 APPROVED+FAILED 到 FAILED。SUCCEEDED 在一个事务内推进 Project Revision、TemplateState 与 lastAppliedChangeSetId。相同终态和相同 Diagnostics 的重复 Result 幂等成功；其他重复 Result 报 CHANGESET_RESULT_CONFLICT。

Plan、Approve、Result 要求 Idempotency-Key，键为 principalId、projectId、operation、key。Service 必须先查询幂等记录，再执行 Revision、状态和权限状态迁移判断：相同键和规范化请求返回原响应；相同键不同请求报 IDEMPOTENCY_CONFLICT。Simulate 不保存幂等记录。

认证使用服务端配置 Token 的 SHA-256 摘要映射 principalId、principalType、scopes，不保存明文 Token。Scope 固定为 template.plan、template.simulate、template.approve、template.read、template.apply-result；只有 XCODE_AGENT 加 template.apply-result 能提交 Result。

Stage3 仅通过 MockMvc/Service 集成测试模拟 Result，不实现 Agent Apply 或恢复。

必须覆盖：首次/并发 Plan、首次 Simulate、Revision 冲突、PLANNED Supersede、NO_CHANGE 清理旧 PLANNED、APPROVED 阻塞、非法 Approve/Result、重复 Result、认证、Scope、幂等、事务回滚、Service/Core 结果一致。

## 6. XcodeAgent 外部参考设计

本章为非交付设计，不实现、不测试、不计入本项目完成标准。

建议外部执行方：

    获取 APPROVED ChangeSet
    → Workspace Lock
    → 读取本地 State
    → 校验 fromRevision
    → 写 pending-apply.json
    → Workspace Preflight
    → 顺序 Apply
    → Blocking Validation
    → 提交 Result
    → Service 确认 APPLIED
    → 原子推进本地 State
    → 删除 Pending

Pending 建议字段为 schemaVersion、projectId、changeSetId、fromRevision、toRevision、phase。Service 为 APPLIED 时本地 finalize；为 APPROVED 时可从首个 Operation 对账重放；为 FAILED、SUPERSEDED、NOT_FOUND 时进入 LOCAL_STATE_DIVERGED。Validation 失败后 Workspace 必须显式恢复或丢弃，不自动回滚。

XcodeAgent 不得 Resolve Capability、修改 Operation 顺序或自行选择 Template Source。

## 7. V1 完成标准

1. Loader 拒绝旧 Manifest、非法 Source、未声明模板文件和 Extension/Route/Interceptor 冲突，不提供兼容层。
2. Login 可独立生成和构建，不再反向依赖 Authorization。
3. 阶段一通过 TemplateSourceContractTest。
4. 阶段二仅通过 ./validation/verify-stage2.sh 完成真实临时 Workspace 闭环。
5. 阶段三完成 Service HTTP、状态机、Revision、事务、认证、幂等和 OpenAPI 验收。
6. XcodeAgent 只保留参考设计，不计入本项目 V1 完成范围。
7. 新增 Capability、Point、State 字段、Operation 或错误码前，必须先扩展本文、Schema、Fixture 与验收测试。
