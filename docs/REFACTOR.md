# XcodeAgent 可插拔模板工程重构方案

> 实施规范。目标：从 `main/auth/...` 多模板分支演进为 **单 Base + 声明式 Capability + 唯一 Composition Engine + 可持续 Reconcile**。

## 1. 目标

1. Base 只维护一套，公共代码不再在多分支重复修改。
2. Login、Authorization、Tracking 等能力通过 Capability 插拔。
3. Capability 自由组合，不预生成组合版本。
4. 首次返回完整工程；后续返回 `TemplateChangeSet`，不覆盖整个工程。
5. Generate、Reconcile、Upgrade 共用同一份 Capability 定义和同一个 `template-engine-core`。
6. XcodeAgent/Skill 不维护能力固定代码，只表达项目需要什么。
7. 组合结果可确定性验证，并支持状态、并发、审计和 EDD 验收。

V1 不做：任意源码 Patch、LLM 组合、Capability 独立 Registry、跨 schema 自动迁移、数据库自动回滚、lockfile 字节级完全可复现、Engine 直接写真实 Workspace。

---

## 2. 总体架构与职责

```text
XcodeAgent / Skill
      ↓ Requested Config
Template Engine Service
      ↓
template-engine-core
      ↓
Capability Resolve → Composition Engine → Desired State → Diff
      ↓                                      ↓
首次 Full Artifact                     后续 ChangeSet
      └──────────────────┬───────────────────┘
                         ↓
                     XcodeAgent
                         ↓
                     Workspace
```

| 模块 | 唯一职责 |
|---|---|
| Base | 定义稳定工程结构 |
| Capability | 声明对 Base 的增量 |
| Composition Engine | 唯一负责组合 |
| Generated Definitions | 把类型化 Contribution 渲染成 Managed Code |
| Skill | 识别能力变化、更新 Requested Config |
| Template Engine | Resolve/Compose/Canonicalize/Diff |
| XcodeAgent | CAS 校验并事务化 Apply ChangeSet |
| Validation | 验证组合结果，不实现组合 |

**硬约束：组合逻辑只能存在于 `template-engine-core`。**

禁止在 Skill、XcodeAgent、Velocity、CI 中实现第二套 Capability 组合。

---

## 3. Monorepo

第一阶段同仓、分模块：

```text
xcodeagent-template/
├── template-source/
│   ├── template.yaml
│   ├── base/
│   ├── capabilities/
│   ├── extension-points/registry.yaml
│   ├── generated-definitions/
│   └── schemas/
├── template-engine/
│   ├── template-engine-core/
│   ├── template-engine-service/
│   └── template-engine-cli/
├── validation/
│   ├── capabilities/
│   ├── combinations/
│   ├── reconciliations/
│   └── rules/
└── .github/workflows/
```

同仓只解决研发、联调和 CI 效率，不改变逻辑边界。未来 Engine 独立 SLA/团队/多模板复用时再拆仓。

---

## 4. Base 与 Managed Area

Base 是完整、真实、可直接运行的 React/Spring Boot 工程：

```text
base/
├── frontend/
│   ├── src/
│   │   ├── pages/
│   │   ├── apis/
│   │   ├── router/
│   │   └── generated/
│   │       ├── providers.tsx
│   │       ├── bootstrap.ts
│   │       ├── routes.tsx
│   │       └── capabilities.ts
│   ├── package.json
│   ├── AGENTS.md
│   └── project-structure.md
└── backend/
    ├── src/
    ├── pom.xml
    ├── AGENTS.md
    └── project-structure.md
```

Base 只负责：
- 稳定目录和公共基础设施；
- 作为 XcodeAgent 业务代码生成的结构参考；
- 提供少量 Managed Extension Area；
- 无 Capability 时也可运行。

生命周期：

```text
首次：Base -> Seed Workspace
后续：Base 不再整体覆盖 Workspace
```

后续只自动变更：
- `generated/**`；
- Capability-owned 文件；
- 结构化依赖节点；
- 文档 Contribution；
- Tool-derived Artifact。

`pages/**`、业务 Service/Controller 等 User-owned 代码不自动覆盖。

Base 中默认 `generated/**` 只是 Derived Snapshot，唯一事实源是 `generated-definitions/**`；CI 必须验证空 Capability 渲染结果与 Base 默认文件一致。

---

## 5. Capability

Capability 是对 Base 的声明式 Desired-State 增量，可稀疏组织：

```text
capabilities/authorization/
├── capability.yaml
├── config.schema.json
├── files/
├── migrations/
└── agent-rules.md

capabilities/tracking/
├── capability.yaml
└── config.schema.json
```

不要求每个 Capability 都有前端、后端、文件和 Migration。

### 5.1 `capability.yaml`

示例：

```yaml
id: authorization
version: 1.2.0
configSchema: config.schema.json

requires: [login]

files:
  - source: files/frontend/src/authorization
    target: frontend/src/authorization
    owner: authorization

dependencies:
  npm:
    - section: dependencies
      name: "@company/auth-core"
      version: "1.2.0"

contributions:
  - point: frontend.providers
    id: auth-provider
    order: 200
    value:
      name: AuthProvider
      importPath: "@/authorization/AuthProvider"

migrations:
  - migrations/authorization.sql

agentRules:
  - agent-rules.md
```

Capability 只能声明“最终需要什么”，不能写命令式 `install/uninstall` 流程。

---

## 6. Config：Requested 与 Effective 必须分离

### 6.1 RequestedTemplateConfig

保存用户真实意图，包括 disabled Capability 的历史参数：

```json
{
  "capabilities": {
    "tracking": {
      "enabled": false,
      "appId": "A"
    }
  }
}
```

### 6.2 EffectiveTemplateConfig

由 Engine 执行：

```text
Schema Validate
→ Defaults
→ requires/conflicts
→ Normalize
→ Effective Config
```

它才参与 Composition。

### 6.3 State-only Change

若：

```text
tracking.enabled=false, appId=A
→
tracking.enabled=false, appId=B
```

Desired Code 不变，但 Requested Config 变了，必须允许：

```text
workspaceOperations = []
stateTransition = STATE_ONLY_CHANGE
stateRevision = 7 -> 8
```

分别保存：

```text
requestedConfigFingerprint
effectiveConfigFingerprint
```

**空 `workspaceOperations` 不等于 No-op。**

---

## 7. Capability Config Schema

每个带动态参数的 Capability 必须有自己的 `configSchema`：

```yaml
id: tracking
configSchema: config.schema.json
```

规则：
1. JSON Schema Draft 2020-12。
2. 默认 `additionalProperties=false`。
3. Resolve 前完成默认值、校验和规范化。
4. `enabled=true` 校验完整必填参数。
5. `enabled=false` 可在 Requested Config 保留参数，但无关字段不进入 Effective Config。
6. `x-sensitive=true` 的值只能保存 `secretRef`；Secret 明文不进入 State、日志或 fingerprint。
7. Capability 参数通过类型化 Contribution 映射到 Renderer，不把整个原始 Config 任意暴露给 Velocity。

---

## 8. `requires` / `conflicts` 解析语义

Capability 开关是：

```text
ABSENT    用户未表达
ENABLED   用户显式 true
DISABLED  用户显式 false
```

### 8.1 多来源 Required Dependency

Resolver 每次从 Requested Config 和完整依赖图**重新计算** activation，不增量修改旧 activation。

例如 Authorization、Workflow 都依赖 Login：

```yaml
login:
  enabled: true
  activation:
    explicit: false
    requiredBy:
      - authorization
      - workflow
```

`requiredBy`：

- 是排序后的去重集合；
- 可以有多个来源；
- 上游 Capability 删除后重新计算。

Effective Enabled 条件：

```text
explicit == true
OR
requiredBy 非空
```

如果用户显式 `login=true`：

```yaml
activation:
  explicit: true
  requiredBy:
    - authorization
```

即使 Authorization 后续删除，Login 仍因为 `explicit=true` 保留。

### 8.2 显式关闭

若：

```text
authorization=ENABLED
login=DISABLED
```

直接：

```text
REQUIRED_CAPABILITY_EXPLICITLY_DISABLED
```

显式关闭永远优先于自动补依赖。

### 8.3 自动启用但缺少必填配置

若 Required Capability 为 ABSENT，被 Resolver 自动激活，但其 Config Schema 存在无法通过默认值补齐的必填参数：

```text
REQUIRED_CAPABILITY_CONFIG_MISSING
```

错误必须返回：

```text
requiredCapabilityId
requiredBy[]
missingFields[]
```

Resolver 不猜参数，也不生成非法 Effective Config。用户必须显式补齐该 Capability 配置后重新请求。

### 8.4 requires 循环

V1 禁止任何 `requires` 环。

Dependency Resolver 在激活前做有向图 cycle detection；发现：

```text
A -> B -> C -> A
```

返回：

```text
CAPABILITY_DEPENDENCY_CYCLE
```

并返回 cycle path。

V1 不把循环解释为“合法共同激活”，也不通过遍历顺序解决。

### 8.5 conflicts

命中 `conflicts`：

```text
CAPABILITY_CONFLICT
```

直接拒绝组合。

---

## 9. Capability 文件与依赖冲突

两个 Capability 直接声明同一 target：

```text
FILE_OWNERSHIP_CONFLICT
```

多个能力共同影响公共文件必须通过：

```text
Contribution → Extension Point → Generated Definition
```

同一 Dependency Key：
- 值完全相同：合并 owners；
- 任一语义字段不同：`DEPENDENCY_CONFLICT`；
- V1 不做自动 SemVer/Maven Range 仲裁；
- 禁止 last-write-wins。

---

## 10. Dependency Desired State

`package.json`、`pom.xml` **不整体托管**，采用：

> 结构化 Dependency Desired State + 节点级 provenance + 对称 Document ChangeOp。

NPM：

```yaml
npm:
  - section: dependencies
    name: "@company/auth-core"
    version: "1.2.0"
    owners: [authorization]
```

NPM Node Key：

```text
(section, packageName)
```

例如：

```text
(dependencies, @company/auth-core)
```

Engine 不拥有整个 `package.json`；用户可以自行增加其他 dependency/script。

### 10.1 Maven 节点唯一键

所有节点先做 default normalization，再计算 key。

**Dependency Key**

```text
(groupId, artifactId, type, classifier)
```

其中：

```text
type 默认 jar
classifier 默认 ""
```

`version/scope/optional/exclusions` 是节点值，不属于 identity。

**Property Key**

```text
property.name
```

**Plugin Key**

```text
(groupId, artifactId)
```

`groupId` 缺省时规范化为：

```text
org.apache.maven.plugins
```

**Plugin Execution Key**

```text
(pluginKey, executionId)
```

V1 要求所有 Capability-owned execution 必须显式提供唯一 `executionId`。

**Plugin / Execution Configuration Node Key**

```text
(ownerNodeKey, configurationJsonPointer)
```

`configuration` 使用规范化 JSON/XML Tree；`configurationJsonPointer` 使用 RFC 6901 风格路径。

V1 对同一路径下重复同名 XML sibling 不做细粒度共享 Ownership；这类列表视为一个完整 configuration node 管理。

### 10.2 Maven 支持字段

Dependency：

```text
groupId/artifactId/version/type/classifier/scope/optional/exclusions
```

Plugin：

```text
groupId/artifactId/version/extensions/executions/configuration
```

不允许任意 XML Patch。

### 10.3 对称 ChangeOp

V1 必须同时提供 Upsert/Delete：

```text
UPSERT_MAVEN_DEPENDENCY
DELETE_MAVEN_DEPENDENCY

UPSERT_MAVEN_PROPERTY
DELETE_MAVEN_PROPERTY

UPSERT_MAVEN_PLUGIN
DELETE_MAVEN_PLUGIN

UPSERT_MAVEN_PLUGIN_EXECUTION
DELETE_MAVEN_PLUGIN_EXECUTION

UPSERT_MAVEN_CONFIGURATION_NODE
DELETE_MAVEN_CONFIGURATION_NODE
```

Capability Remove 依据 Target Desired State 计算 Delete，不写命令式 uninstall。

所有节点只有在 Target `owners=[]` 时才能删除；共享节点继续保留。

---

## 11. Extension Point：只允许 Schema-Typed Contribution

V1 禁止：

```text
Capability -> arbitrary fragment.vm -> raw source
```

必须：

```text
Capability YAML
→ Extension Point Payload Schema
→ Schema-Typed Contribution
→ Composition
→ Generated Definition
→ Source Code
```

原因：只有结构化模型才能统一排序、去重、转义、import 聚合、前置校验和稳定 fingerprint。

统一 Envelope：

```text
Contribution {
  point
  id
  capabilityId
  order
  payload
}
```

---

## 12. Extension Point Registry

所有 `generated-definitions/**` 都必须在 Registry 中有对应契约：

```text
template-source/
├── extension-points/registry.yaml
└── schemas/extension-points/*.schema.json
```

V1 注册表：

| Point | Payload 最小字段 | Renderer | Target |
|---|---|---|---|
| `frontend.providers` | `name, importPath` | `providers.tsx.vm` | `frontend/src/generated/providers.tsx` |
| `frontend.app_bootstrap` | `importPath, importName, arguments` | `bootstrap.ts.vm` | `frontend/src/generated/bootstrap.ts` |
| `frontend.routes` | `routeId, path, componentName, importPath` | `routes.tsx.vm` | `frontend/src/generated/routes.tsx` |
| `frontend.capabilities` | `id, version` | `capabilities.ts.vm` | `frontend/src/generated/capabilities.ts` |
| `backend.capabilities` | `name, className` | `capabilities.java.vm` | `backend/.../generated/Capabilities.java` |
| `backend.bootstrap` | `className, methodName, arguments` | `bootstrap.java.vm` | `backend/.../generated/Bootstrap.java` |
| `backend.migrations` | `id, path, order, sha256` | `migrations.json.vm` | `backend/src/main/resources/xcodeagent/generated/migrations.json` |

Registry 示例：

```yaml
extensionPoints:
  frontend.providers:
    cardinality: many
    payloadSchema: schemas/extension-points/provider.schema.json
    renderer: generated-definitions/frontend/providers.tsx.vm
    target: frontend/src/generated/providers.tsx
    orderBy: [order, capabilityId, id]
```

排序统一：

```text
(order, capabilityId, contributionId)
```

同 Point 下 `contributionId` 唯一。V1 不引入 `before/after`。

新动态能力必须先注册 Extension Point + Schema + Renderer，不能直接开放 raw code。

---

## 13. Velocity 边界

Velocity 只消费已经完成 Schema 校验、Resolve、排序后的 Contribution 列表：

```text
Composition Engine = 决定 WHAT
Velocity           = 渲染 HOW
```

禁止 Velocity：
- 判断 Capability 是否启用；
- 处理 `requires/conflicts`；
- 仲裁依赖版本；
- 决定 Contribution 顺序；
- 枚举 Capability 组合。

---

## 14. Template Engine

```text
template-engine/
├── template-engine-core/
│   ├── source/
│   ├── capability/
│   ├── composition/
│   ├── canonical/
│   ├── state/
│   ├── diff/
│   └── validation/
├── template-engine-service/
└── template-engine-cli/
```

Core 只能理解通用模型：

```text
Capability
Schema
Dependency
Contribution
Extension Point
Managed File/Node
Desired State
ChangeSet
```

禁止：

```java
if (capabilityId.equals("authorization")) ...
installAuthorization();
```

Service 和 CLI 必须共用同一个 Core：

```text
Runtime: XcodeAgent -> Service -> Core
CI:      GitHub Actions -> CLI -> Core
```

---

## 15. Core Pipeline

```text
Requested Config
   ↓ Capability Config Schema
Defaults / Canonicalize
   ↓
requires/conflicts Resolve
   ↓
Effective Config
   ↓
Capability Load + Dependency Resolve
   ↓
Composition Engine
   ↓
Extension Point Validate / Sort
   ↓
Generated Definition Render
   ↓
Desired Template State
   ↓
State Diff
   ↓
Full Assembly Plan / TemplateChangeSet
```

---

## 16. Canonicalization 与 Fingerprint

Canonicalization 分为两个独立阶段，禁止把 Requested 与 Effective 混成一个“统一规范化流程”。

### 16.1 Requested Config Canonicalization

Requested Config 表示**用户显式意图**。

处理流程：

```text
Raw Requested Config
→ JSON 语法/类型校验
→ Reject Unknown Fields
→ 保留 ABSENT / ENABLED / DISABLED 和用户显式参数
→ RFC 8785 JCS
→ requestedConfigFingerprint
```

明确：

- **不 Apply Defaults**；
- **不 Resolve `requires/conflicts`**；
- **不自动插入隐式 Required Capability**；
- 用户未提供的字段保持 absent；
- 用户显式填写且与默认值相同，仍属于 Requested Intent 的一部分。

因此 `requestedConfigFingerprint` 只描述“用户提交了什么”，不描述 Resolver 最终推导结果。

### 16.2 Effective Config Canonicalization

Effective Config 表示真正参与 Composition 的配置。

处理流程：

```text
Canonical Requested Config
→ Apply Capability Defaults
→ Resolve requires/conflicts
→ 自动激活 Required Capability
→ 校验自动激活 Capability 的必填配置
→ Normalize activation provenance
→ 去除 disabled 状态下不影响当前组合的参数
→ RFC 8785 JCS
→ effectiveConfigFingerprint
```

默认值、隐式 Required Capability、`activation.requiredBy` 都只属于 Effective Config。

### 16.3 通用 JSON 规则

两类 Config 最终序列化都使用 **RFC 8785 JCS**：

- Object key 按 JCS；
- UTF-8；
- 数字规范化；
- 数组默认保留顺序；
- 仅 Schema 标记 `x-set=true` 时按 canonical value 排序去重。

### 16.4 Managed Text

Engine 生成文本固定：

```text
UTF-8 / no BOM / LF / final newline
```

File hash 对最终实际字节求 SHA-256。

### 16.5 Desired State Fingerprint

对规范 Manifest 求 hash，至少覆盖：

```text
templateSourceCommit
schemaVersion
engineProtocolVersion
effectiveConfigFingerprint

managed files: path/contentSha256/owner/executable
managed nodes: path/nodeKey/canonicalValue/owners
capabilities: id/version
postApplyActions: type/normalized parameters
```

路径统一 `/` 且 Workspace-relative。

Canonicalization 规则变化必须提升 `engineProtocolVersion`。

---

## 17. 确定性承诺与缓存键

必须区分两个确定性函数。

### 17.1 Desired-State Determinism

```text
F(
  templateSourceCommit,
  engineProtocolVersion,
  effectiveTemplateConfig
)
→ DesiredTemplateState
```

相同输入必须得到相同：

```text
DesiredTemplateState
desiredStateFingerprint
```

因此 Desired State 缓存键可以是：

```text
templateSourceCommit
+ engineProtocolVersion
+ effectiveConfigFingerprint
```

### 17.2 ChangeSet Determinism

ChangeSet 是“从当前状态到目标状态”的 Diff，不只依赖目标配置。

```text
G(
  currentRequestedConfig,
  currentTemplateState,
  currentManagedState,
  targetRequestedConfig,
  targetDesiredState,
  baseStateRevision,
  baseDesiredStateFingerprint
)
→ TemplateChangeSet
```

只有 **完整 Diff 输入相同** 时，才承诺 ChangeSet 语义相同。

同一个 Target Config 从不同 Current State 出发，得到不同 ChangeSet 是正确行为。

ChangeSet 的语义缓存键至少覆盖：

```text
current requestedConfigFingerprint
current desiredStateFingerprint
current managedStateFingerprint
target requestedConfigFingerprint
target desiredStateFingerprint
baseStateRevision
engineProtocolVersion
```

`requestId` 是幂等键，不属于 ChangeSet 语义 fingerprint。

### 17.3 外部工具边界

Template Engine 不承诺所有外部工具生成 Artifact 的最终字节相同；例如 lockfile 属于 Tool-derived Artifact。确定性承诺止于 Desired State / ChangeSet 语义。

---

## 18. Lockfile：Tool-derived Artifact

`pnpm-lock.yaml` 不属于 Composition Engine 直接生成的 Deterministic File。

模板固定：

```yaml
toolchain:
  frontend:
    nodeMajor: 24
    packageManager: pnpm
    packageManagerVersion: 10.15.0
    registryPolicy: company-default
```

依赖变化产生：

```text
REFRESH_PACKAGE_LOCK
```

Apply 时 XcodeAgent 在隔离目录使用固定 Node/pnpm/registry 生成 lockfile。

因此：
- `desiredStateFingerprint` 不包含未来 lockfile 的 content SHA；
- fingerprint 包含 Dependency Desired State + Node/pnpm 版本 + registry policy；
- Apply 成功后计算真实 lockfile SHA，写入 Managed State；
- lockfile 被人工改动时默认重新生成，不做三方 merge；
- 刷新失败返回 `PACKAGE_LOCK_REFRESH_FAILED`，State 不前移。

若未来要求 bit-for-bit 可复现，需要 immutable registry snapshot；V1 不做。

---

## 19. 五个核心状态模型

### 19.1 RequestedTemplateConfig
用户真实意图。

### 19.2 EffectiveTemplateConfig
经过 Defaults、requires/conflicts、Normalize 后真正参与组合的配置。

### 19.3 DesiredTemplateState
至少包含：

```text
files
structuredDependencies
contributions
generatedFiles
agentRules
structureMetadata
postApplyActions
```

### 19.4 ManagedStateManifest
记录平台拥有的：
- Managed File；
- Managed Structured Node；
- Tool-derived Artifact；
- owner/provenance/hash。

### 19.5 TemplateState

```json
{
  "templateVersion": "2.3.0",
  "templateSourceCommit": "4f8c1a...",
  "templateSourceDisplayRef": "refs/tags/template-v2.3.0",
  "schemaVersion": 1,
  "engineProtocolVersion": 1,
  "requestedConfigFingerprint": "...",
  "effectiveConfigFingerprint": "...",
  "desiredStateFingerprint": "...",
  "managedStateFingerprint": "...",
  "stateRevision": 7
}
```

真正组合依据只有：

```text
templateSourceCommit
```

Display Ref 只用于展示/审计。

---

## 20. Workspace 持久文件

```text
.xcodeagent/
├── template-config.json       # Requested Canonical Config
├── template-state.json
├── managed-state.json
├── template-structure.json    # Template Engine 管理的平台结构元数据
├── transactions/              # XcodeAgent Apply Journal/Backup
└── template-change-log.json   # 可选，只存元数据
```

不长期保存：

```text
delta.zip
inline/delta 临时 Payload
临时解压目录
```

---

## 20.1 Reconcile Current-State 一致性校验

Template Engine 在计算旧 Desired State 前必须验证 Current Config / Template State / Managed State 是同一个已提交状态。

固定校验顺序：

```text
1. request.templateSourceCommit
   == currentTemplateState.templateSourceCommit

2. request.engineProtocolVersion
   == currentTemplateState.engineProtocolVersion

3. JCS(currentTemplateConfig)
   → fingerprint
   == currentTemplateState.requestedConfigFingerprint

4. 用 pinned Source + current Requested Config 重新 Resolve
   → effectiveConfigFingerprint
   == currentTemplateState.effectiveConfigFingerprint

5. 重新 Compile Current Desired State
   → desiredStateFingerprint
   == currentTemplateState.desiredStateFingerprint

6. canonical(managedStateManifest)
   → managedStateFingerprint
   == currentTemplateState.managedStateFingerprint

7. baseStateRevision
   == currentTemplateState.stateRevision
```

任一不一致直接失败，不进入 Diff。

错误码：

```text
CURRENT_CONFIG_STATE_MISMATCH
CURRENT_EFFECTIVE_STATE_MISMATCH
CURRENT_DESIRED_STATE_MISMATCH
MANAGED_STATE_MISMATCH
STALE_TEMPLATE_STATE
```

这一步验证“请求携带的历史状态本身可信且互相一致”；真正 Workspace 当前文件是否被用户修改，仍由 Apply 阶段 File/Node CAS 判断。

---

## 21. CAS 与并发保护

必须三层保护。

### 21.1 Workspace State CAS

ChangeSet 带：

```text
baseStateRevision
baseDesiredStateFingerprint
```

不匹配：

```text
STALE_TEMPLATE_STATE
```

### 21.2 Managed File CAS

```json
{
  "precondition": {
    "type": "FILE_SHA256_EQUALS",
    "sha256": "..."
  }
}
```

不匹配：

```text
MANAGED_FILE_CONFLICT
```

### 21.3 Managed Node CAS

结构化文档不能用整文件 SHA。

已有节点：

```json
{
  "operation": "UPSERT_JSON_NODE",
  "path": "frontend/package.json",
  "nodeKey": "/dependencies/@company~1auth-core",
  "precondition": {
    "type": "SEMANTIC_HASH_EQUALS",
    "hash": "..."
  },
  "value": "1.2.0"
}
```

新增节点：

```text
precondition = NODE_ABSENT
```

删除节点要求：

```text
semantic hash 未变
AND target owners == []
```

否则：

```text
MANAGED_NODE_CONFLICT
```

这样用户修改同一文件的其他节点不会造成冲突，也不会被覆盖。

---

## 21.4 Conflict 后的恢复语义

File/Node CAS 冲突时：

```text
本次 ChangeSet = ABORTED
Workspace State = 不前移
Managed State = 仍保持上一次成功提交的 baseline
```

V1 不自动“吸收”用户对 Managed 内容的修改，也不静默释放 Ownership。

允许三种显式处理：

1. **RESTORE_MANAGED_BASELINE**  
   XcodeAgent 根据 pinned Current Desired State 恢复 Engine 上次管理值，然后重新 Reconcile。

2. **FORCE_APPLY_TARGET**  
   用户明确确认放弃冲突的本地 Managed 修改后，XcodeAgent 以 Target Managed Value 覆盖，再完成 Apply。必须留下审计记录。

3. **CANCEL**  
   保持 Workspace 和 Template State 不变。后续相关 Reconcile 仍会继续报告冲突，直到人工处理。

V1 不支持：

```text
DETACH_OWNERSHIP
ADOPT_ARBITRARY_LOCAL_VALUE_AS_MANAGED
自动三方 merge Managed Source
```

若业务确实需要用户可定制，应把可变部分建模为 Config、User-owned 文件或正式 Extension Point，而不是长期修改 Managed 内容。

---

## 22. TemplateChangeSet

```text
TemplateChangeSet
├── metadata
├── workspaceOperations
├── postApplyActions
├── stateTransition
└── payloadRef / inlinePayload
```

V1 operation 至少包括：

```text
ADD_FILE / UPDATE_FILE / DELETE_FILE
UPSERT_JSON_NODE / DELETE_JSON_NODE
UPSERT_MAVEN_DEPENDENCY / DELETE_MAVEN_DEPENDENCY
UPSERT_MAVEN_PROPERTY
UPSERT_MAVEN_PLUGIN
```

`postApplyActions` 至少包括：

```text
REFRESH_PACKAGE_LOCK
```

`stateTransition` 必须支持：

```text
STATE_ONLY_CHANGE
```

因此真正 No-op 必须同时满足 Requested、Effective、Desired State 均无变化。

---

## 23. API 唯一契约

所有请求统一包含：

```text
projectId
requestId
engineProtocolVersion
```

其中 `projectId` 是 XcodeAgent 的稳定逻辑项目标识，用于幂等域和审计，不作为模板组合输入。

### 23.1 Generate

```text
POST /templates/generate
```

```json
{
  "projectId": "project-123",
  "requestId": "req-123",
  "engineProtocolVersion": 1,
  "templateSourceRef": "refs/tags/template-v2.3.0",
  "templateConfig": {}
}
```

Engine：

```text
Ref -> resolve once -> immutable Commit
```

输出：

```text
Full Artifact
Canonical Requested Config
Template State
Managed State
```

### 23.2 Reconcile

```text
POST /templates/reconcile
```

Reconcile 不接受可变 Ref：

```json
{
  "projectId": "project-123",
  "requestId": "req-456",
  "engineProtocolVersion": 1,
  "templateSourceCommit": "4f8c1a...",
  "currentTemplateConfig": {},
  "currentTemplateState": {},
  "managedStateManifest": {},
  "targetTemplateConfig": {},
  "baseStateRevision": 7,
  "baseDesiredStateFingerprint": "9c31f2..."
}
```

输出：

```text
TemplateChangeSet
Canonical Target Requested Config
Next Template State Plan
Next Managed State Plan
```

### 23.3 Upgrade

```text
POST /templates/upgrade
```

```json
{
  "projectId": "project-123",
  "requestId": "req-789",
  "engineProtocolVersion": 1,

  "currentTemplateSourceCommit": "4f8c1a...",
  "targetTemplateSourceRef": "refs/tags/template-v2.4.0",

  "currentTemplateConfig": {},
  "currentTemplateState": {},
  "managedStateManifest": {},
  "targetTemplateConfig": {},

  "baseStateRevision": 7,
  "baseDesiredStateFingerprint": "9c31f2..."
}
```

`targetTemplateConfig` 省略时默认使用 Current Requested Config；但仍必须通过目标 Source 的 Config Schema。

Service 先把：

```text
targetTemplateSourceRef
→ targetTemplateSourceCommit
```

固定后再执行 Upgrade。

输出：

```text
targetTemplateSourceCommit
TemplateChangeSet
Canonical Target Requested Config
Next Template State Plan
Next Managed State Plan
```

V1 Upgrade 仍受 §25 同 schemaVersion / Config 可直接验证约束。

### 23.4 Preview

```text
POST /templates/reconcile/preview
POST /templates/upgrade/preview
```

DTO 与对应正式 API 相同，只返回 ChangeSet/State Preview，不生成最终 Artifact、无 Workspace 副作用。

### 23.5 Idempotency Scope

幂等键不是全局裸 `requestId`。

真实 Key：

```text
(
  authenticatedCaller,
  projectId,
  operationType,
  requestId
)
```

`operationType` 独立命名空间：

```text
GENERATE
RECONCILE
UPGRADE
PREVIEW_RECONCILE
PREVIEW_UPGRADE
```

因此不同 API、不同项目、不同调用方不会互相碰撞。

行为：

```text
同 Idempotency Key + 同 canonical request hash
→ 返回首次语义结果

同 Idempotency Key + 不同 request hash
→ IDEMPOTENCY_KEY_REUSED
```

V1 默认保留：

```text
Generate/Reconcile/Upgrade idempotency record: 24h
Preview idempotency/cache record: 10min
```

幂等记录必须存放在服务端持久化/共享存储，服务重启不能立即丢失。

在幂等有效期内，用于首次结果的 Artifact/Payload 必须仍可获取，或服务能够依据已保存的 semantic result + payload digest 重新签发下载引用；不能因为临时 URL 过期而改变幂等语义。

---

## 24. 三条生命周期流程

### 24.1 Generate

Generate 的 Template Engine Service 自己承担临时工程 Materialization，因此必须执行 postApplyActions：

```text
Source Ref -> Commit
+ Requested Config
→ Effective Config
→ Composition
→ Desired State
→ Full Assembly Plan
→ Service 创建临时工程目录
→ Apply File / Structured Document Operations
→ Service 执行 postApplyActions
   └── 例如 REFRESH_PACKAGE_LOCK
→ build / structure validation
→ 计算 Tool-derived Artifact 实际 hash
→ 生成最终 Managed State
→ Package Full Artifact
→ 返回 XcodeAgent
```

因此首次 Full Artifact 内：

```text
package.json
pnpm-lock.yaml
Managed State
```

必须来自同一次 Materialize 结果，不能出现依赖与 lockfile 不一致。

Generate 中的 postApplyActions 执行者是：

```text
template-engine-service / Materialization Runner
```

它只操作服务端临时工程，不写用户真实 Workspace。

### 24.2 Reconcile

```text
Pinned Commit
+ Current Config/State/Managed State
+ Target Requested Config
→ Current State Consistency Validate
→ Target Effective Config
→ Composition
→ Target Desired State
→ Diff
→ ChangeSet
→ XcodeAgent CAS + Transaction Apply
→ XcodeAgent 执行 postApplyActions
```

普通 Reconcile 不重新拉 Base，不切换 Commit。

### 24.3 Explicit Upgrade

```text
Old Commit + Current Config
→ Old Desired State

Target Ref -> Target Commit
+ Target Config
→ New Desired State

Old -> New
→ Managed-safe ChangeSet
→ XcodeAgent Transaction Apply
```

---

## 25. Template Source Upgrade V1

1. Commit 是真实依据，Tag 仅展示/选择。
2. 旧 Commit 必须可获取；否则 `SOURCE_SNAPSHOT_UNAVAILABLE`。
3. V1 只支持 `old.schemaVersion == new.schemaVersion`。
4. Current Config 必须能直接通过新 Capability Config Schema。
5. Capability rename/delete/split 不自动映射。
6. 不满足 3~5：`CONFIG_MIGRATION_REQUIRED`。
7. 不允许 LLM 临时推断配置迁移。
8. 未来如增加 `capability-migrations.yaml`，必须版本化、确定性、可测试。

已被工程引用的 Source Commit 必须长期可获取；生产化可额外归档 Source Snapshot。

---

## 26. XcodeAgent Apply：Crash-Recoverable Transaction

V1 不宣称文件系统支持“多文件 OS 级原子提交”；目标是实现：

> **单写者 + 全量预检 + Journal + Backup + State-last + 崩溃可恢复。**

### 26.1 Single Writer

每个 Workspace 的模板变更必须获取：

```text
.xcodeagent/template-mutation.lock
```

同一 Workspace 同时只能有一个 Template Apply。

### 26.2 Transaction 目录

每次 Apply 创建：

```text
.xcodeagent/transactions/{transactionId}/
├── journal.json
├── backup/
└── staged/
```

`journal.json` 至少记录：

```text
transactionId
changeSetId
baseStateRevision
phase
affectedPaths[]
targetFingerprints
startedAt
```

Phase：

```text
PREPARED
APPLYING
STATE_COMMITTED
COMMITTED
ROLLED_BACK
```

### 26.3 Apply 顺序

```text
1. 获取 mutation lock
2. 校验 Current State 一致性
3. 全量校验 File/Node CAS
4. 备份全部目标文件 + 三个状态文件
5. 在 staged/ 准备文件和 Document ChangeOp
6. 执行 postApplyActions
7. 执行必要 build/structure validation
8. journal -> APPLYING
9. 每个目标使用 temp-file + fsync + rename 写回
10. 最后写 template-config/state/managed-state
11. TemplateState.lastAppliedTransactionId = transactionId
12. journal -> STATE_COMMITTED
13. 校验目标 fingerprints
14. journal -> COMMITTED
15. 清理 backup/staged，释放 lock
```

状态文件始终最后写。

### 26.4 崩溃恢复

XcodeAgent 启动或获取 mutation lock 前扫描未完成 Transaction。

若 phase：

```text
PREPARED / APPLYING
```

使用 `backup/` 恢复文件和 State，标记 `ROLLED_BACK`。

若：

```text
STATE_COMMITTED
```

检查：

```text
TemplateState.lastAppliedTransactionId == transactionId
AND target fingerprints 全部成立
```

成立则补记 `COMMITTED`；否则使用 Backup 回滚。

`COMMITTED` 但未清理的 Transaction 只做垃圾清理。

因此最终保证的是**崩溃一致性和可恢复性**，而不是虚假的跨多文件单指令原子性。

---

## 27. Artifact 生命周期

首次：

```text
Full Artifact ZIP
```

后续：

```text
TemplateChangeSet
```

Payload：
- 小：inline；
- 大：临时 Delta Artifact。

Delta 只是传输载体，建议 TTL 10 分钟~1 小时，Apply 后删除。

长期保存：

```text
Workspace
Template Config
Template State
Managed State
ChangeSet metadata / Git history
```

---

## 28. Capability Remove 与 Migration

Capability Remove 仍然是：

```text
Current Desired State
→ Target Desired State
→ Diff
```

File/Dependency/Generated 内容均按 Ownership 与 Target State 处理，不存在 Capability 特殊 uninstall。

### 28.1 Migration V1 契约

Migration 不能只声明一个源文件。V1 固定为静态 Managed Migration Asset：

```yaml
migrations:
  - id: authorization-schema
    source: migrations/authorization.sql
    target: backend/src/main/resources/xcodeagent/migrations/authorization/001-schema.sql
    order: 100
    mode: COPY
```

字段：

| 字段 | 规则 |
|---|---|
| `id` | Capability 内唯一；全局 identity 为 `(capabilityId,id)` |
| `source` | Template Source 内相对路径 |
| `target` | Workspace-relative，且全局唯一 |
| `order` | 整数，稳定排序 |
| `mode` | V1 只支持 `COPY` |

V1 Migration 不允许任意 Velocity SQL Template；动态初始化逻辑应使用正式类型化 Bootstrap Extension Point。

Composition 后 Migration 进入 Desired State：

```text
migrationId
capabilityId
target
order
sourceContentSha256
mode
```

并参与 `desiredStateFingerprint`。

稳定顺序：

```text
(order, capabilityId, migrationId)
```

Target 冲突：

```text
MIGRATION_TARGET_CONFLICT
```

### 28.2 Registration

Template Engine 自动把启用 Migration 投影到类型化：

```text
backend.migrations
```

Extension Point，生成：

```text
backend/src/main/resources/xcodeagent/generated/migrations.json
```

最小记录：

```json
{
  "id": "authorization:authorization-schema",
  "path": "xcodeagent/migrations/authorization/001-schema.sql",
  "order": 100,
  "sha256": "..."
}
```

生成应用的 Bootstrap/Migration Runner 只消费该 Manifest。

因此：

```text
Migration Asset
→ Managed File
Migration Registration
→ Generated Managed Manifest
```

### 28.3 Remove

关闭 Capability 时：

- Migration 文件只有当前 hash 与 Managed State 一致才从源码工程删除；
- Manifest 条目按 Target Desired State 删除；
- **已经在真实数据库执行过的 DDL/DML 不自动回滚**。

数据库执行/回滚属于应用 Migration/Bootstrap 或 Build/Deploy 生命周期，不属于 Template Engine。

---

## 29. AGENTS.md / project-structure.md / Skill

### 29.1 AGENTS.md

`AGENTS.md` 固定为 **Template Engine 整文件 Managed Derived File**：

```text
AGENTS.md
= Base Agent Rules
+ Enabled Capability Agent Rules
```

不允许 Agent/User 直接修改 Managed `AGENTS.md`。

项目自定义规则写入独立 User-owned：

```text
AGENTS.user.md
```

Base 的 Managed `AGENTS.md` 固定包含：

```text
项目自定义规则如存在，继续读取 AGENTS.user.md。
```

因此 Capability Reconcile 可以安全重建 `AGENTS.md`，而不覆盖用户自定义内容。

### 29.2 project-structure.md

`project-structure.md` 是随业务代码持续变化的 Living Project Structure，归 **XcodeAgent/User-owned**，Template Engine 不整文件管理。

Template Engine 只维护：

```text
.xcodeagent/template-structure.json
```

内容是 Base/Capability 带来的平台结构元数据，例如：

```json
{
  "paths": [
    {
      "path": "frontend/src/authorization",
      "owner": "authorization",
      "agentWritable": false
    }
  ]
}
```

XcodeAgent 的 Workspace Scan / Project Structure 更新流程读取：

```text
实际 Workspace
+
template-structure.json
```

生成/更新 `project-structure.md`。

因此：

```text
Template Engine -> template-structure.json
XcodeAgent       -> project-structure.md
```

不存在双方同时 Ownership 同一个文档的问题。

### 29.3 Skill

Skill 只做：

```text
理解用户意图
→ 修改 Requested Template Config / Technical Plan
→ 调 Template Engine
```

Skill 不知道具体 TSX/Java/Dependency/Generated 文件。

能力代码唯一 Source of Truth：

```text
template-source/capabilities/**
```

---

## 30. 路径与 Ownership 安全

- Source/Target 必须 Workspace-relative；
- 禁止 `../`；
- 禁止 symlink 逃逸，V1 不创建 symlink；
- DELETE 只能作用于 Managed State 中有 Ownership 的 File/Node；
- Capability 不得覆盖 User-owned File；
- 共享路径必须通过 Generated/Structured Model 表达。

---

## 31. Validation / CI

组合者：

```text
template-engine-core / Composition Engine
```

验收者：

```text
validation/** + CI
```

Core Runtime Validation：
- Config/Capability Schema；
- requires/conflicts；
- File Ownership；
- Dependency Conflict；
- Extension Point 是否注册；
- Contribution Payload Schema；
- Contribution ID；
- Path Security；
- Desired State Integrity。

CI Test Suite 至少覆盖：
- Base；
- 单 Capability；
- 典型/非法组合；
- Reconcile；
- State-only Change；
- Remove；
- Upgrade；
- Managed File/Node Conflict；
- Lockfile Refresh；
- AGENTS/project-structure；
- npm build；
- mvn verify。

CI 必须走：

```text
template-engine-cli -> template-engine-core
```

不得实现第二套 Composer。

---

## 32. Error Contract

V1 至少统一：

```text
INVALID_TEMPLATE_CONFIG
UNKNOWN_CAPABILITY
CAPABILITY_CONFLICT
REQUIRED_CAPABILITY_EXPLICITLY_DISABLED

FILE_OWNERSHIP_CONFLICT
DEPENDENCY_CONFLICT
UNKNOWN_EXTENSION_POINT
INVALID_CONTRIBUTION_PAYLOAD
DUPLICATE_CONTRIBUTION_ID

STALE_TEMPLATE_STATE
MANAGED_FILE_CONFLICT
MANAGED_NODE_CONFLICT

IDEMPOTENCY_KEY_REUSED

SOURCE_SNAPSHOT_UNAVAILABLE
CONFIG_MIGRATION_REQUIRED
UNSUPPORTED_SCHEMA_VERSION
UNSUPPORTED_ENGINE_PROTOCOL

PACKAGE_LOCK_REFRESH_FAILED

REQUIRED_CAPABILITY_CONFIG_MISSING
CAPABILITY_DEPENDENCY_CYCLE

MIGRATION_TARGET_CONFLICT

CURRENT_CONFIG_STATE_MISMATCH
CURRENT_EFFECTIVE_STATE_MISMATCH
CURRENT_DESIRED_STATE_MISMATCH
MANAGED_STATE_MISMATCH

TEMPLATE_TRANSACTION_RECOVERY_FAILED
WORKSPACE_MUTATION_IN_PROGRESS

INVALID_WORKSPACE_PATH
```

错误必须结构化，不允许调用方解析日志文本判断状态。

---

## 33. 版本模型

第一阶段 Capability 与 Template Source 同仓发布。

真正决定使用哪份 Capability 源码：

```text
templateSourceCommit
```

`capability.yaml.version` 主要用于审计、State、兼容性和未来 Registry 演进。

V1 禁止同一个工程：

```text
Source Commit=A
但单独指定 authorization@B
```

逻辑上始终区分：

```text
Template Source Version
templateSourceCommit
schemaVersion
engineProtocolVersion
```

`template.yaml` 示例：

```yaml
schemaVersion: 1

template:
  id: xcodeagent-standard
  version: 2.3.0

toolchain:
  frontend:
    nodeMajor: 24
    packageManager: pnpm
    packageManagerVersion: 10.15.0
    registryPolicy: company-default
```

---

## 34. 最终硬约束

1. 一个 Monorepo，逻辑分 Source / Engine / Validation。
2. Composition Engine 是唯一组合中心。
3. Capability 只声明 Desired-State Contribution。
4. Skill/XcodeAgent 不维护 Capability 固定代码。
5. Velocity 只渲染 Schema-Typed Contribution。
6. 每个 Generated Definition 必须有 Extension Point Registry + Payload Schema。
7. Requested Canonicalization 不 Apply Defaults、不 Resolve；Effective Canonicalization 才做 Defaults/Dependency Resolve。
8. `requestedConfigFingerprint` 与 `effectiveConfigFingerprint` 语义严格分离。
9. Desired State 确定性与 ChangeSet 确定性是两个函数；ChangeSet 必须包含完整 Current-State Diff 输入。
10. Reconcile 只接受 immutable `templateSourceCommit`。
11. Ref 只在 Generate/Upgrade 选择版本时出现，随后立即解析为 Commit。
12. disabled 参数变化允许 State-only ChangeSet。
13. `requires` 使用 ABSENT/ENABLED/DISABLED；显式 DISABLED 禁止自动开启。
14. 自动激活 Required Capability 缺少必填配置时返回 `REQUIRED_CAPABILITY_CONFIG_MISSING`。
15. `activation.requiredBy` 是多来源集合，每次 Resolve 从完整图重新计算。
16. V1 禁止 `requires` cycle。
17. `package.json` / `pom.xml` 使用节点级 Ownership。
18. Maven Dependency/Property/Plugin/Execution/Configuration Node 都有固定逻辑 Key 和对称 Upsert/Delete。
19. Managed File 使用 File SHA CAS。
20. Managed Node 使用 Semantic Hash / NODE_ABSENT CAS。
21. Shared Dependency 通过 owners 管理，owners 为空才删除。
22. 文件/依赖冲突 fail-closed，禁止 last-write-wins。
23. Migration 必须声明 id/source/target/order/mode，并进入 `backend.migrations` Manifest。
24. Config Canonicalization 使用 RFC 8785 JCS。
25. Engine 生成文本统一 UTF-8/LF。
26. 相同 Commit + Protocol + Effective Config 只承诺相同 Desired State；ChangeSet 还依赖完整 Current State。
27. Lockfile 是 Tool-derived Artifact，不承诺字节级确定性。
28. Generate 必须在服务端临时工程执行 postApplyActions 后才能打包 Full Artifact。
29. Engine Service 不直接修改真实 Workspace。
30. Reconcile 前必须验证 Current Config/Template State/Managed State fingerprint 链一致。
31. AGENTS.md 是 Engine Whole-file Managed；用户自定义写 `AGENTS.user.md`。
32. `project-structure.md` 归 XcodeAgent；Engine 只管理 `.xcodeagent/template-structure.json`。
33. Managed Conflict 不自动吸收用户改动；V1 只支持 Restore/Force/Cancel。
34. XcodeAgent Apply 使用 single-writer + journal + backup + state-last 的 crash-recoverable transaction。
35. Template Source Upgrade 必须显式触发，并有独立 `/templates/upgrade` API。
36. V1 Upgrade 仅支持同 schemaVersion 且 Config 可直接验证。
37. 其他升级返回 `CONFIG_MIGRATION_REQUIRED`。
38. 幂等作用域为 caller + project + operation + requestId；正式请求 V1 默认保留 24h。
39. Base 只首次 Seed，不持续覆盖 User-owned 业务代码。
40. Delta Artifact 只是一过性 Payload。
41. Migration 的真实数据库执行/回滚不属于 Template Engine。
42. CI 必须复用同一个 `template-engine-core`。
43. 模板代码只有一个 Source of Truth：`template-source/**`。

---

## 35. 最终状态转换模型

```text
Requested Config
      ↓
Requested Canonicalize
      ↓
Defaults + requires/conflicts
      ↓
Effective Config
      ↓
Composition Engine
      ↓
Desired Template State
      ↓
┌──────────────┬─────────────────────────────┐
│ Generate     │ Reconcile / Upgrade         │
│ Full Plan    │ Validate Current State      │
│              │ → State Diff                │
└──────┬───────┴─────────────┬───────────────┘
       │                     ↓
       │             TemplateChangeSet
       │                     │
       │             XcodeAgent File/Node CAS
       │                     │
       │             Crash-Recoverable Apply
       │                     │
       └─────────────┬───────┘
                     ↓
                  Workspace
                     ↓
 Requested Config + Template State + Managed State
```

Generate 额外执行：

```text
Service Temporary Materialization
→ postApplyActions
→ Validation
→ Full Artifact
```

最终概括：

> **Base 定结构，Capability 定增量，Composition Engine 唯一负责组合，Skill 定触发，Template Engine 负责确定性状态计算，XcodeAgent 负责带 CAS 与 Journal 的事务化落地。**
