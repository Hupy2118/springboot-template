# Template Service Capability Reconcile 方案设计与实施计划（V2）

> 适用对象：Template Service / Template Engine  
> 核心职责：根据 `Requested Config + Current TemplateState + Template Release` 生成确定性的 Capability Modification Strategy。  
> 明确边界：Template Service **不感知 Workspace，不读取 Workspace，不接收 Workspace 文件内容，不执行实际文件修改**。

---

# 第一章 方案设计

## 1. 目标与核心原则

Template Service 的职责必须收敛为：

```text
Requested Config
+
Current TemplateState
+
Current Template Release
        ↓
Capability Reconcile Decision
        ↓
Modification Strategy
+
Candidate nextTemplateState
+
Validation Plan
```

核心原则：

> Template Service 不维护 Workspace 文件历史所有权，也不要求 Workspace 与任何历史模板版本一致。Service 只负责根据 Capability 状态和当前 Template Release，生成确定性的文件修改策略；真正的代码事实源始终在 XCodeAgent 所持有的当前 Workspace 中。

因此 Service 不应包含：

```text
Workspace Snapshot
Workspace Context
Workspace SHA
Current File Content
Journal
Apply
Rollback
Workspace Lock
AST / 文本实际修改
```

这些全部属于 XCodeAgent。

---

## 2. Service 职责边界

Template Service 负责：

```text
Capability Definition
Capability Dependency Resolution
Capability Config Canonicalization
Reconcile Reason 判断
Capability Removal Guard
Addition CREATE / MAINTAIN 分类
Release Identity 判断
Modification Strategy 生成
Validation Plan 生成
Candidate nextTemplateState 生成
Update Package 生成
```

Template Service 不负责：

```text
读取 Workspace
判断当前文件是否存在
比较文件 SHA
执行 Transformer
执行 AST 修改
执行文本修改
生成文件级 Journal
Apply 文件
Rollback 文件
执行构建 / 测试命令
提交 TemplateState
```

---

## 3. TemplateState V2

正式 State：

```json
{
  "schemaVersion": 2,
  "templateRevision": "R3",
  "releaseDigest": "sha256:...",
  "requested": {
    "authorization": {
      "enabled": true,
      "config": {}
    }
  },
  "effective": {
    "login": {
      "enabled": true,
      "config": {}
    },
    "authorization": {
      "enabled": true,
      "config": {}
    }
  },
  "appliedAdditions": {
    "login.login-page": {
      "capabilityId": "login",
      "target": "frontend/src/pages/Login/index.tsx",
      "installedRevision": "R3"
    }
  }
}
```

State 只保存：

```text
Capability 状态
Addition 生命周期事实
Template Release Identity
```

State 不保存：

```text
managedFiles
Workspace 文件内容
Workspace 文件 SHA
历史模板文件内容
历史代码基线
```

---

## 4. Release Identity

Release Identity：

```text
(templateRevision, releaseDigest)
```

其中：

```text
releaseDigest
=
Template Source
+
Capability Metadata
+
Strategy Definition Registry
+
Validator Definition Registry
```

的 canonical SHA-256。

判断规则：

```text
State revision != Current revision
→ RELEASE_REFRESH

State revision == Current revision
且 State digest == Current digest
→ Release Identity 一致

State revision == Current revision
但 State digest != Current digest
→ TEMPLATE_RELEASE_REVISION_REUSED
```

同一个 revision 不允许对应不同 Release 实现。

---

## 5. Capability Definition

每个 Capability V2 Metadata 至少包含：

```yaml
id: authorization
schemaVersion: 2

defaultConfig: {}

requires:
  - id: login

existingTargets:
  - path: frontend/src/App.tsx
    strategyId: frontend.ensure-auth-provider
    order: 100

additions:
  - id: authorization.role-page
    source: files/frontend/src/pages/RoleManagementPage.tsx
    target: frontend/src/pages/RoleManagementPage.tsx
    maintainPolicy:
      mode: NO_OP

  - id: authorization.auth-provider
    source: files/frontend/src/providers/AuthProvider.tsx
    target: frontend/src/providers/AuthProvider.tsx
    maintainPolicy:
      mode: STRATEGY
      strategyId: frontend.authorization.reconcile-auth-provider
      order: 300

validators:
  - validatorId: authorization.frontend
    order: 100
```

注意：

```text
existingTargets
```

在 Service 中只代表：

> “该 Capability 需要对这个路径执行某种 Strategy”。

它不代表：

```text
Service 要读取这个文件
Service 要确认这个文件存在
Service 要生成修改后的完整文件
```

文件是否存在、Strategy 能否在当前代码上执行，都由 XCodeAgent 决定。

---

## 6. Config 与依赖解析

统一 Capability State：

```json
{
  "enabled": true,
  "config": {}
}
```

Config 规则：

```text
显式 requested config
>
Capability defaultConfig
```

依赖自动引入：

```text
authorization
requires login
```

则：

```text
requested:
  authorization

effective:
  login
  authorization
```

执行顺序按：

```text
dependency topology
→ capabilityId stable order
```

Config 不允许在 normalize 阶段丢失。

---

## 7. Reconcile Reason

Service 判断：

```text
ENABLE
CONFIG_CHANGE
RELEASE_REFRESH
HEALTH_REPAIR
NO_CHANGE
```

### ENABLE

```text
target effective
出现 current effective 中不存在的 Capability
```

### CONFIG_CHANGE

```text
Capability 仍存在
但 canonical config 发生变化
```

### RELEASE_REFRESH

```text
Template Release Identity 变化
```

### HEALTH_REPAIR

调用：

```json
{
  "mode": "RECONCILE"
}
```

时，即使 State 未变化，也重新返回 Capability Strategy，让 XCodeAgent 在当前 Workspace 上执行幂等修复。

### NO_CHANGE

仅当：

```text
无 ENABLE
无 CONFIG_CHANGE
无 RELEASE_REFRESH
mode != RECONCILE
```

才成立。

---

## 8. Capability Removal

V1 不支持 Capability Removal。

若：

```text
current effective - target effective != empty
```

返回：

```text
CAPABILITY_REMOVAL_UNSUPPORTED
```

Service 不生成：

```text
DELETE_FILE
```

也不生成逆向 Strategy。

---

## 9. Addition 生命周期

Addition 使用稳定：

```text
additionId
```

Service 根据：

```text
TemplateState.appliedAdditions
```

分类：

```text
不存在
→ CREATE

已存在
→ MAINTAIN
```

### CREATE

Service 返回：

```json
{
  "type": "ADD_FILE",
  "additionId": "login.login-page",
  "target": "frontend/src/pages/Login/index.tsx",
  "sourceRef": "payload/login/login-page.tsx",
  "precondition": "TARGET_MUST_NOT_EXIST"
}
```

是否真的不存在，由 XCodeAgent 执行时判断。

### MAINTAIN

Service 不把 Addition Source 当作覆盖当前文件的完整目标态。

MAINTAIN 行为必须由：

```text
Addition Metadata.maintainPolicy
```

唯一确定：

```text
NO_OP
或
STRATEGY
```

Decision Engine 不允许自行选择。

---

## 10. Addition MAINTAIN 确定性契约

Service 不允许在运行时自行选择：

```text
NO_OP
或
TRANSFORM_FILE
```

每个 Addition 必须在 Metadata 中显式声明：

```yaml
maintainPolicy:
  mode: NO_OP
```

或：

```yaml
maintainPolicy:
  mode: STRATEGY
  strategyId: frontend.authorization.reconcile-auth-provider
  order: 300
```

V1 只支持两种模式：

```text
NO_OP
STRATEGY
```

### 10.1 `NO_OP`

语义：

```text
Addition 已经安装后
Service 不再为该 Addition 本身生成文件修改 Strategy
```

这不代表文件一定健康。

文件健康由：

```text
Validation Plan
```

负责检查。

因此：

```text
MAINTAIN + NO_OP
→ 不产生 Modification Strategy
→ candidate nextState 保持该 additionId
```

### 10.2 `STRATEGY`

语义：

```text
Addition 已经安装后
每次该 Capability 被纳入本次 Reconcile
都生成固定的维护 Strategy
```

输出：

```json
{
  "type": "TRANSFORM_FILE",
  "additionId": "authorization.auth-provider",
  "target": "frontend/src/providers/AuthProvider.tsx",
  "strategyId": "frontend.authorization.reconcile-auth-provider",
  "order": 300
}
```

真正读取和修改文件仍由 XCodeAgent 完成。

### 10.3 Schema 硬约束

每个 Addition：

```text
maintainPolicy 必填
```

规则：

```text
mode=NO_OP
→ 不允许 strategyId

mode=STRATEGY
→ strategyId 必填
→ strategyId 必须存在于 StrategyRegistry
```

禁止：

```text
maintainPolicy 缺失
→ Service 根据文件类型猜

maintainPolicy 缺失
→ 默认 NO_OP

maintainPolicy 缺失
→ 根据 source 是否变化决定
```

因此对于相同：

```text
State + RequestedConfig + Release
```

Service 的 MAINTAIN 输出唯一确定。

### 10.4 Source 演进

Addition `source` 可以随 Release 变化，但：

```text
MAINTAIN
```

不能因为 source 改变而隐式覆盖已存在文件。

规则：

```text
CREATE
→ 使用当前 Release source

MAINTAIN + NO_OP
→ 不使用 source

MAINTAIN + STRATEGY
→ 使用固定 strategyId
→ 不把 source 当作完整目标文件覆盖
```

如果未来确实需要“重新以 Source 为基准维护”，必须新增显式 Policy 类型，不能复用 V1 的 `STRATEGY` 或静默覆盖。

---

## 11. Addition Identity

V1 固定：

```text
Addition Identity
=
(additionId, capabilityId, target)
```

如果同一 `additionId` 在新 Release 中修改：

```text
capabilityId
或
target
```

返回：

```text
ADDITION_IDENTITY_CHANGED
```

V1 不支持：

```text
Move
Delete old target
Rename
silent retarget
```

---

## 12. Modification Strategy

Service 返回的核心不是“修改后的文件”，而是：

```text
Modification Strategy
```

推荐 Strategy 分为有限 DSL，而不是任意脚本。

示例类型：

```text
ADD_FILE
ENSURE_IMPORT
ENSURE_NPM_DEPENDENCY
ENSURE_MAVEN_DEPENDENCY
ENSURE_REACT_PROVIDER
ENSURE_ROUTE
ENSURE_MENU_ITEM
ENSURE_SPRING_BEAN
ENSURE_INTERCEPTOR
TEXT_ANCHOR_INSERT
```

示例：

```json
{
  "type": "TRANSFORM_FILE",
  "target": "frontend/src/App.tsx",
  "strategy": {
    "type": "ENSURE_REACT_PROVIDER",
    "provider": "AuthProvider",
    "import": {
      "name": "AuthProvider",
      "from": "./providers/AuthProvider"
    }
  }
}
```

Service 只定义：

```text
要实现什么结构
```

不定义：

```text
当前文件具体怎么改
```

具体 AST / parser / text transformation 在 XCodeAgent。

---

## 13. Strategy 顺序

Service 必须返回确定性顺序：

```text
dependency topology
→ capability order
→ strategy.order
→ strategyId
```

同一路径允许有多个 Strategy：

```text
App.tsx
  login.ensure-provider
  authorization.ensure-wrapper
```

Service 只保证顺序。

真正串行作用于当前文件 Working Copy 的逻辑属于 XCodeAgent。

---

## 14. Validation Plan

Service 生成：

```text
Validation Plan
```

例如：

```json
{
  "validators": [
    {
      "type": "FILE_EXISTS",
      "path": "frontend/src/pages/Login/index.tsx"
    },
    {
      "type": "NPM_BUILD",
      "workingDirectory": "frontend"
    },
    {
      "type": "MAVEN_TEST",
      "workingDirectory": "backend"
    }
  ]
}
```

Service 只生成计划，不执行。

执行和失败处理由 XCodeAgent 负责。

---

## 15. `/v1/update`

当前正式更新入口：

```text
POST /v1/update
```

Request：

```json
{
  "currentTemplateState": {
    "...": "TemplateState V2"
  },
  "requestedConfig": {
    "...": "..."
  },
  "mode": "APPLY"
}
```

Service 内部：

```text
Validate State
→ Resolve Config
→ Resolve Dependencies
→ Check Release Identity
→ Resolve Reconcile Reason
→ Removal Guard
→ Classify Additions
→ Build Modification Strategy
→ Build Validation Plan
→ Build candidate nextTemplateState
```

### NO_CHANGE

返回：

```http
204 No Content
```

### CHANGE

返回 Strategy Package。

---

## 16. `/v1/update/plan`

预留接口，只用于未来：

```text
查询变更
预览变更
展示将影响哪些文件
展示 Capability 变化
```

它不是当前 XCodeAgent 的更新前置步骤。

未来如果开放：

```text
/v1/update/plan
```

必须复用：

```text
ReconcileDecisionEngine
```

但只能返回 Read-only Preview。

真正执行 `/v1/update` 时必须重新计算 Decision。

---

## 17. Update Package

推荐结构：

```text
update-package.zip
├── manifest.json
├── modification-strategy.json
├── next-template-state.json
├── validation-plan.json
└── payload/
```

`payload/` 只承载：

```text
ADD_FILE 所需的新文件内容
静态资源
必要模板片段
```

不承载：

```text
Current Workspace 文件
已经修改完成的 Existing Target 全文件
```

---

## 18. `/v1/generate`

Generate 负责：

```text
Base 工程物化
+
Capability 首次生成
+
TemplateState V2 Bootstrap
```

Generate 可以直接生成完整新工程，因为此时还不存在用户 Workspace 历史代码。

Generate 流程：

```text
Resolve Config
→ Dependency Closure
→ Base Layer
→ Capability Addition
→ Initial Strategy Application
→ Generated Layer
→ Final Project Tree
→ Validation Plan
→ appliedAdditions
→ TemplateState V2
→ Generate ZIP
```

注意：

```text
Generate 阶段的 Initial Strategy
```

可以由 Service 内部 Generate Engine 对模板 Working Tree 执行，因为这里的代码事实源就是本次新建模板树，而不是已有用户 Workspace。

这与 Update 的职责不冲突：

```text
Generate
→ Service 可生成完整新工程

Update
→ Service 只生成 Strategy
→ XCodeAgent 在现有 Workspace 执行
```

---

# 第二章 实施计划

## TS-0：冻结协议

冻结：

```text
TemplateState V2
Capability Metadata V2
Modification Strategy Schema
Addition maintainPolicy Schema
Validation Plan Schema
Update Package
Error Codes
```

验收：

```text
JSON Schema / OpenAPI Contract Test 全绿
```

---

## TS-1：建立 V2 Domain Model

新增：

```text
TemplateStateV2
CapabilityState
AppliedAdditionState
ReconcileReason
ModificationStrategy
ValidationPlan
UpdateResult
```

硬约束：

```text
V2 Domain 不得出现 managedFiles
```

---

## TS-2：建立 Capability Metadata V2

新增：

```text
CapabilityV2Loader
CapabilityRegistryV2
StrategyRegistry
ValidatorRegistry
```

Metadata 支持：

```text
defaultConfig
requires
existingTargets
additions
additions.maintainPolicy
validators
```

验收：

```text
duplicate additionId
maintainPolicy missing
NO_OP with strategyId
STRATEGY without strategyId
unknown strategyId
unknown validatorId
unsafe path
dependency cycle
```

全部 fail-closed。

---

## TS-3：实现 ReconcileDecisionEngine

输入：

```text
TemplateStateV2
RequestedConfig
Mode
Current Template Release
CapabilityRegistryV2
```

输出：

```text
NO_CHANGE
或
ReconcileDecision
```

Decision 包含：

```text
reasons
effective
capabilitiesToApply
additionActions
strategyDescriptors（含 Metadata 决定的 MAINTAIN Strategy）
validationPlan
candidate nextTemplateState
```

测试：

```text
{} → login
{} → authorization
repeat APPLY → NO_CHANGE
repeat RECONCILE → HEALTH_REPAIR
config change
release refresh
removal reject
```

---

## TS-4：实现 Modification Strategy Builder

将 Metadata 转成：

```text
ADD_FILE
TRANSFORM_FILE
ENSURE_*
```

Strategy Descriptor。

硬约束：

```text
Service 不读取 Workspace
Service 不执行 Strategy
```

测试必须证明：

```text
Strategy 构建不需要任何 Workspace 文件内容
```

---

## TS-5：实现 Addition CREATE / MAINTAIN

依据：

```text
appliedAdditions
```

先确定生命周期：

```text
不存在 → CREATE
已存在 → MAINTAIN
```

CREATE 固定：

```text
CREATE
→ ADD_FILE
```

MAINTAIN 不允许 Decision Engine 自行选择行为，而是严格读取：

```text
Addition Metadata.maintainPolicy
```

映射：

```text
MAINTAIN + NO_OP
→ 不生成该 Addition 的修改 Strategy

MAINTAIN + STRATEGY
→ 生成 Metadata 指定 strategyId 的 TRANSFORM_FILE
```

增加：

```text
ADDITION_IDENTITY_CHANGED
```

验收：

```text
首次 Addition → CREATE
重复 Addition + NO_OP → 无修改 Strategy
重复 Addition + STRATEGY → 固定 TRANSFORM_FILE
maintainPolicy missing → Metadata load fail
STRATEGY missing strategyId → Metadata load fail
same State + Config + Release → Strategy 输出完全一致
same id + different target → reject
```

---

## TS-6：实现 Release Refresh / Config Change

支持：

```text
CONFIG_CHANGE
RELEASE_REFRESH
```

Refresh 只重新生成 Strategy，不读取 Workspace。

---

## TS-7：实现 `/v1/update`

Controller：

```text
request
→ ReconcileDecisionEngine
→ NO_CHANGE: 204
→ CHANGE: Build Update Package
```

禁止出现：

```text
workspaceSnapshot
workspaceContext
workspaceFiles
current file content
sha256 of Workspace file
```

---

## TS-8：实现 Update Package

固定：

```text
manifest.json
modification-strategy.json
next-template-state.json
validation-plan.json
payload/**
```

Package Build 必须原子：

```text
全部成功
→ HTTP 200

任一步失败
→ 不返回半包
```

---

## TS-9：实现 GenerateCoreV2

Generate：

```text
Base
→ Addition
→ Initial Strategy
→ Generated Layer
→ Final Tree
→ State V2
```

Generate State 必须包含正确：

```text
appliedAdditions
releaseDigest
```

---

## TS-10：切换生产 `/v1/generate`

新工程：

```text
从第一天就是 TemplateState V2
```

不再生成：

```text
managedFiles
```

---

## TS-11：切换生产 `/v1/update`

生产 Update：

```text
只接受 TemplateState V2
```

当前不兼容旧 State。

旧 State：

```text
TEMPLATE_STATE_SCHEMA_UNSUPPORTED
```

---

## TS-12：预留 `/v1/update/plan`

当前可以：

```text
只保留 API 设计
或
暂不实现
```

未来实现时复用：

```text
ReconcileDecisionEngine
```

不进入当前 Update 主链路。

---

## TS-13：Acceptance Matrix

至少覆盖：

```text
Generate login
Generate authorization
Update NO_CHANGE
Enable login
Enable authorization
Config Change
Release Refresh
RECONCILE
Removal reject
Addition CREATE
Addition MAINTAIN + NO_OP
Addition MAINTAIN + STRATEGY
maintainPolicy 缺失拒绝
相同 State + Config + Release 的 MAINTAIN 输出确定性一致
Addition identity changed
Update Package build failure
Unsupported State Schema
```

特别增加架构门禁：

```text
/v1/update Request Schema
不得出现 Workspace 文件内容相关字段
```

---

## TS-14：清理 Legacy 更新语义

最终生产代码删除：

```text
managedFiles
targetFiles vs managedFiles diff
DELETE_FILE
workspaceSnapshot
workspaceContext
requiredWorkspaceFiles
Service-side current-file Transformer
```

Architecture Test：

```text
ReconcileDecisionEngine
ModificationStrategyBuilder
UpdateController
PackageBuilder
```

不得依赖 Workspace Runtime 类。

最终还必须满足：

```text
Addition CREATE / MAINTAIN
由 State 决定

MAINTAIN 后具体做什么
由 Metadata.maintainPolicy 决定

Decision Engine 不包含任何基于运行时猜测的分支
```

最终 Service 数据流应固定为：

```text
Config + State + Release
        ↓
Deterministic Decision
        ↓
Deterministic Modification Strategy
        ↓
Update Package
```
