# Template Service Capability Reconcile 方案设计与实施计划（V2｜Wire Contract 收口版）

> 适用对象：Template Service / Template Engine 与 XCodeAgent Template Reconcile V2  
> 核心职责：根据 `Requested Config + Current TemplateState + Current Template Release` 生成确定性的 Capability Modification Strategy。  
> 明确边界：Template Service **不感知 Workspace，不读取 Workspace，不接收 Workspace 文件内容，不执行实际文件修改**。  
> **协议冻结决策：以 XCodeAgent 当前 `StrategyUpdatePackageV2` 为唯一最终 Wire Contract。** Template Service 的 `/v1/update` 必须直接生成 XCodeAgent 可以严格解析和执行的协议，不再维护另一套 Service 私有 Update Package Wire Schema。

---

## 本版变更说明

本版在原《XCodeAgent_Template_Capability_增量更新方案_双端实施计划_断点修复版》基础上进行协议收口，核心变化如下：

1. `/v1/update` Request 固定增加 `protocolVersion: "2"`，与 XCodeAgent 当前调用一致。
2. Update ZIP 从原先四个独立元数据文件：

   ```text
   manifest.json
   modification-strategy.json
   next-template-state.json
   validation-plan.json
   ```

   收口为唯一：

   ```text
   strategy-update-package.json
   ```

   以及显式声明的 `payload/**`。
3. `strategy-update-package.json` 的 DTO 以 XCodeAgent 当前 `StrategyUpdatePackageV2` 为唯一权威定义。
4. `Modification Strategy` 的 Wire Type 固定为 XCodeAgent 当前支持的有限 DSL；`TRANSFORM_FILE`、`RENDER_EXTENSION` 不允许继续作为 Wire Type。
5. Strategy 的 `order` 仅作为 Service 内部排序依据；Wire Contract 使用从 `0` 开始连续的 `index` 表示最终执行顺序。
6. Validation Plan 改为 XCodeAgent 当前 `ValidationPlanItemV2[]` 的严格结构，不再返回 `{ "validators": [...] }` 包装结构。
7. 增加 `currentStateDigest / nextStateDigest / payloadManifest / packageId / diagnostics` 等双端绑定字段。
8. 冻结 TemplateState digest 算法，确保 Service 与 XCodeAgent 对同一 State 得到完全相同的摘要。
9. `RECONCILE` 固定为**不改变 TemplateState 的健康修复模式**；任何需要 State 变化的场景必须进入 `APPLY`。
10. TS-0、TS-4、TS-7、TS-8、TS-11、TS-13 的实施与验收标准同步调整，优先完成双端协议一致性后再继续后续功能实施。

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
Deterministic Modification Strategy
+
Candidate nextTemplateState
+
Validation Plan
        ↓
StrategyUpdatePackageV2
```

核心原则：

> Template Service 不维护 Workspace 文件历史所有权，也不要求 Workspace 与任何历史模板版本一致。Service 只负责根据 Capability 状态和当前 Template Release 生成确定性的修改意图；真正的代码事实源始终是 XCodeAgent 所持有的当前 Workspace。

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

### 1.1 Wire Contract 单一事实源

V2 双端协议只允许存在一套权威 Wire Contract：

```text
XCodeAgent StrategyUpdatePackageV2
```

Template Service 可以存在自己的 Domain Model、Decision Model、Registry Model，但在 HTTP 边界前必须编译成该 Wire Contract。

禁止：

```text
Service 自己定义一套 Update Package
+
XCodeAgent 再做第二次适配
```

禁止继续保留两套并行 Wire Schema：

```text
Service Package Contract
!=
XCodeAgent Package Contract
```

正确结构：

```text
Service Domain / Metadata
        ↓
ReconcileDecisionEngine
        ↓
Wire Contract Compiler
        ↓
StrategyUpdatePackageV2
        ↓
XCodeAgent strict validate
```

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
Strategy Wire Type 编译
Validation Plan 生成
Candidate nextTemplateState 生成
State Digest 生成
Payload Manifest 生成
StrategyUpdatePackageV2 生成
```

Template Service 不负责：

```text
读取 Workspace
判断当前文件实际内容
比较 Workspace 文件 SHA
执行 Transformer
执行 AST 修改
执行文本修改
生成 Workspace Journal
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
  "releaseDigest": "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
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

### 3.1 State 只保存

```text
Capability 状态
Addition 生命周期事实
Template Release Identity
```

### 3.2 State 不保存

```text
managedFiles
Workspace 文件内容
Workspace 文件 SHA
历史模板文件内容
历史代码基线
origin / GENERATED / UPDATED 等历史来源字段
```

`appliedAdditions` 的 Map Key 固定为稳定 `additionId`，不是 `capabilityId`。

### 3.3 TemplateState Digest 冻结算法

双端必须使用同一算法计算：

```text
currentStateDigest
nextStateDigest
```

算法固定为：

```text
1. 将 TemplateStateV2 序列化为 JSON
2. UTF-8
3. ensure_ascii = false
4. object key 全量 sort_keys = true
5. separators = (",", ":")，不包含多余空白
6. 对最终 bytes 计算 SHA-256
7. 输出：sha256:<64 lowercase hex>
```

等价伪代码：

```text
sha256(
  compact_sorted_utf8_json(TemplateStateV2)
)
```

任何一端不得使用 pretty JSON、字段插入顺序或其他 canonicalization 算法计算 State Digest。

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
Strategy Registry
+
Validator Registry
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

注意：

```text
releaseDigest
```

与：

```text
currentStateDigest / nextStateDigest
```

是两类不同摘要：前者标识 Template Release，后者绑定完整 TemplateStateV2。

---

## 5. Capability Definition 与 StrategyRegistryV2

### 5.1 Capability Metadata

Capability V2 Metadata 只声明 Capability 关系和引用，不重复定义 Strategy 的 target/type/order。

推荐结构：

```yaml
id: authorization
schemaVersion: 2

defaultConfig: {}

requires:
  - id: login

existingTargets:
  - strategyId: frontend.authorization.ensure-provider
  - strategyId: frontend.authorization.ensure-page-route
  - strategyId: frontend.authorization.ensure-menu

additions:
  - id: authorization.role-page
    source: frontend/src/pages/System/AuthorizationManagementPage.tsx
    target: frontend/src/pages/System/AuthorizationManagementPage.tsx
    maintainPolicy:
      mode: NO_OP

  - id: authorization.auth-provider
    source: frontend/src/providers/AuthProvider.tsx
    target: frontend/src/providers/AuthProvider.tsx
    maintainPolicy:
      mode: STRATEGY
      strategyId: frontend.authorization.reconcile-auth-provider

validators:
  - validatorId: authorization.frontend
  - validatorId: authorization.backend
```

### 5.2 StrategyRegistryV2 是 Strategy 的唯一事实源

Strategy 的下列事实只允许由 Registry 定义：

```text
strategyId
Target
Wire Type
Internal order
Parameters / Payload Source
Validator Binding（如有）
```

Capability Metadata 只引用 `strategyId`。

### 5.3 Registry 的 Type 必须可编译为 Wire Type

V2 Wire Contract 不允许输出：

```text
TRANSFORM_FILE
RENDER_EXTENSION
```

这类通用类型最多只能作为旧实现的迁移中间语义，不能跨 HTTP 边界。

最终 Registry 推荐直接使用 Wire Type：

```yaml
strategies:
  - id: frontend.authorization.ensure-provider
    targetId: frontend.capability-providers
    type: ENSURE_REACT_PROVIDER
    order: 200
    parameters:
      managedMarker: "xcodeagent:authorization-provider"
      astSelector:
        kind: "react-provider-root"
    payloadSource: generated/authorization-provider.txt
```

如果现有 Registry 仍保存旧的 `RENDER_EXTENSION / TRANSFORM_FILE`，则必须在实施 TS-4 时完成一次性迁移；运行时不得根据文件后缀或 target 名称猜测 Wire Type。

无法唯一映射时必须 fail-closed：

```text
STRATEGY_WIRE_TYPE_UNSUPPORTED
```

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

## 7. Reconcile Reason 与 Mode 语义

Service 判断：

```text
ENABLE
CONFIG_CHANGE
RELEASE_REFRESH
HEALTH_REPAIR
NO_CHANGE
```

### 7.1 APPLY

`APPLY` 是允许改变 TemplateState 的模式，可处理：

```text
ENABLE
CONFIG_CHANGE
RELEASE_REFRESH
Addition CREATE
其他会导致 nextTemplateState 变化的场景
```

### 7.2 RECONCILE

`RECONCILE` 固定定义为：

> 对当前 State 所描述的 effective Capability 执行幂等健康修复，但**不得改变 TemplateState 的任何语义内容**。

因此必须满足：

```text
currentStateDigest == nextStateDigest
```

并且至少保持：

```text
requested unchanged
effective unchanged
templateRevision unchanged
releaseDigest unchanged
appliedAdditions unchanged
```

`RECONCILE` 可以：

```text
重新输出 Existing Target Strategy
重新输出 MAINTAIN + STRATEGY
重新输出 Validation Plan
```

但不能：

```text
ENABLE 新 Capability
修改 Config
做 Release Refresh State Commit
新增 appliedAdditions
```

如果 `mode=RECONCILE` 时发现需要 State 变化，Service 必须 fail-closed，例如：

```text
RECONCILE_STATE_CHANGE_REQUIRED
```

由调用方重新以 `APPLY` 发起真正的状态变更。

### 7.3 NO_CHANGE

`APPLY` 下仅当：

```text
无 ENABLE
无 CONFIG_CHANGE
无 RELEASE_REFRESH
无其他 State 变化
```

才返回：

```http
204 No Content
```

`RECONCILE` 不因 State 无变化自动返回 204；它的目的就是健康修复，因此正常情况下应返回 Strategy Package。

---

## 8. Capability Removal

V2 当前不支持 Capability Removal。

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

### 9.1 CREATE

CREATE 生成 Wire Strategy：

```json
{
  "strategyId": "authorization.role-page",
  "index": 0,
  "schemaVersion": 1,
  "type": "ADD_FILE",
  "target": "frontend/src/pages/System/AuthorizationManagementPage.tsx",
  "precondition": {},
  "parameters": {
    "additionId": "authorization.role-page",
    "capabilityId": "authorization"
  },
  "payloadRef": "payload/authorization/role-page.tsx"
}
```

注意：

- `precondition` 在 Wire Contract 中固定为 JSON object，禁止继续返回字符串 `"TARGET_MUST_NOT_EXIST"`。
- 当前 ADD_FILE 的 retry-safe 行为由 XCodeAgent 根据“目标不存在 / 已存在且内容相同 / 已存在且内容冲突”三分支执行。
- Service 不读取 Workspace，因此不在服务端判断目标是否存在。

### 9.2 MAINTAIN

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

### 10.1 NO_OP

语义：

```text
Addition 已安装后
Service 不再为该 Addition 本身生成修改 Strategy
```

因此：

```text
MAINTAIN + NO_OP
→ 不产生 Modification Strategy
→ candidate nextState 保持该 additionId
```

文件健康由 Validation Plan 或 Capability 的其他 Strategy 负责。

### 10.2 STRATEGY

语义：

```text
Addition 已安装后
每次该 Capability 被纳入本次 Decision
都使用 Metadata 指定的 strategyId
```

但 Wire 输出不能使用通用：

```text
TRANSFORM_FILE
```

Service 必须通过 StrategyRegistryV2 将该 `strategyId` 编译成唯一支持的 Wire Type，例如：

```json
{
  "strategyId": "frontend.authorization.reconcile-auth-provider",
  "index": 3,
  "schemaVersion": 1,
  "type": "ENSURE_REACT_PROVIDER",
  "target": "frontend/src/providers/AuthProvider.tsx",
  "precondition": {},
  "parameters": {
    "managedMarker": "xcodeagent:authorization-provider",
    "astSelector": {
      "kind": "react-provider-root"
    }
  },
  "payloadRef": "payload/strategies/frontend.authorization.reconcile-auth-provider.txt"
}
```

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
→ strategyId 必须存在于 StrategyRegistryV2
→ strategyId 必须可唯一编译为受支持 Wire Type
```

禁止：

```text
maintainPolicy 缺失 → Service 猜测
maintainPolicy 缺失 → 默认 NO_OP
source 变化 → 自动覆盖 Workspace
target 后缀 → 猜 Strategy Type
```

### 10.4 Source 演进

```text
CREATE
→ 使用当前 Release source 形成 payload

MAINTAIN + NO_OP
→ 不使用 source

MAINTAIN + STRATEGY
→ 使用固定 strategyId
→ 由 Registry 决定 Wire Strategy
→ 不把 source 当作完整目标文件覆盖
```

未来如果需要“重新以 Source 为基准维护”，必须新增显式 Policy，不能复用现有 STRATEGY 语义。

---

## 11. Addition Identity

固定：

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

V2 当前不支持：

```text
Move
Delete old target
Rename
silent retarget
```

---

## 12. Modification Strategy Wire Contract

### 12.1 唯一允许的 Wire Type

固定为 XCodeAgent 当前 `StrategyTypeV2`：

```text
ADD_FILE
TEXT_ANCHOR_INSERT
ENSURE_IMPORT
ENSURE_NPM_DEPENDENCY
ENSURE_MAVEN_DEPENDENCY
ENSURE_REACT_PROVIDER
ENSURE_ROUTE
ENSURE_MENU_ITEM
ENSURE_SPRING_BEAN
ENSURE_INTERCEPTOR
```

禁止作为 Wire Type：

```text
TRANSFORM_FILE
RENDER_EXTENSION
任意脚本类型
任意未注册字符串
```

### 12.2 StrategyDescriptorV2

每条 Strategy 固定结构：

```json
{
  "strategyId": "frontend.authorization.ensure-provider",
  "index": 0,
  "schemaVersion": 1,
  "type": "ENSURE_REACT_PROVIDER",
  "target": "frontend/src/generated/capabilityProviders.tsx",
  "precondition": {},
  "parameters": {},
  "payloadRef": null
}
```

字段语义：

```text
strategyId
→ Package 内唯一逻辑标识

index
→ 最终全局执行顺序，必须从 0 开始连续

schemaVersion
→ 当前固定为 1

type
→ 受支持的 StrategyTypeV2

target
→ Workspace 相对路径

precondition
→ JSON object；不得输出字符串或隐式 DSL

parameters
→ 对应 Strategy Type 的严格参数

payloadRef
→ 可选；若存在必须指向 payloadManifest 中的条目
```

### 12.3 Service 内部 order 与 Wire index

Registry 中可以保留：

```text
order
```

但它不是 Wire 字段。

Service 最终必须先完成确定性排序：

```text
dependency topology
→ capability stable order
→ registry order
→ strategyId
```

然后将排序结果编译为：

```text
index = 0, 1, 2, ... N-1
```

XCodeAgent 不再根据 `order` 二次排序。

### 12.4 Strategy Id 唯一性

同一个 Package：

```text
strategyId 不得重复
index 不得跳号
index 必须与数组顺序一致
```

---

## 13. Strategy 执行责任

Service 只定义：

```text
要实现什么结构
```

XCodeAgent 负责：

```text
读取当前 Workspace 文件
Working Copy
AST / JSON / XML / Text 修改
幂等判断
冲突检测
最终 Apply
失败恢复
```

同一路径允许有多个 Strategy；XCodeAgent 必须严格按 Package `index` 串行作用于同一 Working Copy。

---

## 14. Validation Plan V2

Validation Plan 不再返回：

```json
{
  "validators": []
}
```

Wire Contract 固定为：

```text
validationPlan: ValidationPlanItemV2[]
```

### 14.1 支持的 Validation Type

```text
CAPABILITY_POSTCONDITION
FILE_EXISTS
STRUCTURE_CHECK
JSON_STRUCTURE_CHECK
NPM_BUILD
NPM_TEST
MAVEN_TEST
MAVEN_PACKAGE
```

### 14.2 ValidationPlanItemV2

示例：

```json
{
  "validationId": "authorization.role-page.exists",
  "index": 0,
  "type": "FILE_EXISTS",
  "capabilityId": "authorization",
  "workingDirectory": ".",
  "path": "frontend/src/pages/System/AuthorizationManagementPage.tsx",
  "blocking": true,
  "timeoutSeconds": 30,
  "executionMode": "REAL_WORKSPACE"
}
```

字段根据 type 使用：

```text
FILE_EXISTS
→ path 必填

STRUCTURE_CHECK
→ path + containsAll 必填

JSON_STRUCTURE_CHECK
→ path + pointer 必填

CAPABILITY_POSTCONDITION
→ capabilityId + 非空 checks 必填

NPM_BUILD / NPM_TEST / MAVEN_TEST / MAVEN_PACKAGE
→ 使用 workingDirectory
→ 不携带 path / containsAll / pointer / checks
```

所有 Validation Item：

```text
validationId 唯一
index 从 0 开始连续
blocking 必填
timeoutSeconds > 0
executionMode = REAL_WORKSPACE | SANDBOX
```

Service 只生成计划，不执行。

---

## 15. `/v1/update` Wire Contract

当前正式入口：

```text
POST /v1/update
Content-Type: application/json
Accept: application/zip
```

### 15.1 Request 固定结构

```json
{
  "protocolVersion": "2",
  "currentTemplateState": {
    "schemaVersion": 2,
    "templateRevision": "R3",
    "releaseDigest": "sha256:...",
    "requested": {},
    "effective": {},
    "appliedAdditions": {}
  },
  "requestedConfig": {
    "capabilities": {}
  },
  "mode": "APPLY"
}
```

允许字段必须严格等于：

```text
protocolVersion
currentTemplateState
requestedConfig
mode
```

`protocolVersion`：

```text
必须为字符串 "2"
```

`mode`：

```text
APPLY
或
RECONCILE
```

未知字段一律拒绝。

### 15.2 Service 内部流程

```text
Validate protocolVersion
→ Validate TemplateStateV2
→ Validate requestedConfig
→ Resolve Config
→ Resolve Dependencies
→ Check Release Identity
→ Resolve Mode / Reconcile Reason
→ Removal Guard
→ Classify Additions
→ Build Domain Strategies
→ Compile Wire Strategies
→ Build ValidationPlanItemV2[]
→ Build candidate nextTemplateState
→ Calculate State Digests
→ Build payloadManifest
→ Build StrategyUpdatePackageV2
→ Build immutable ZIP
```

### 15.3 NO_CHANGE

仅 `APPLY` 在没有任何变更时：

```http
204 No Content
```

不得返回空 ZIP。

### 15.4 CHANGE / RECONCILE

返回：

```http
200 OK
Content-Type: application/zip
```

ZIP 必须严格符合第 17 节。

---

## 16. `/v1/update/plan`

预留接口，只用于未来：

```text
查询变更
预览变更
展示将影响哪些文件
展示 Capability 变化
```

它不是当前 XCodeAgent 更新前置步骤。

未来如果开放：

```text
/v1/update/plan
```

必须复用：

```text
ReconcileDecisionEngine
Wire Strategy Compiler
```

但只返回 Read-only Preview。

真正执行 `/v1/update` 时必须重新计算 Decision。

---

## 17. StrategyUpdatePackageV2

### 17.1 ZIP 固定布局

唯一合法结构：

```text
update-package.zip
├── strategy-update-package.json
└── payload/
    └── **
```

其中 payload 可以为空。

明确废弃并禁止继续输出：

```text
manifest.json
modification-strategy.json
next-template-state.json
validation-plan.json
change-set.json
```

### 17.2 `strategy-update-package.json` 固定结构

```json
{
  "protocolVersion": "2",
  "packageId": "pkg-20260911-001",
  "mode": "APPLY",
  "sourceRevision": "R3",
  "currentStateDigest": "sha256:...",
  "nextStateDigest": "sha256:...",
  "strategies": [],
  "validationPlan": [],
  "payloadManifest": {},
  "nextTemplateState": {
    "schemaVersion": 2,
    "templateRevision": "R4",
    "releaseDigest": "sha256:...",
    "requested": {},
    "effective": {},
    "appliedAdditions": {}
  },
  "diagnostics": []
}
```

字段语义：

```text
protocolVersion
→ 固定 "2"

packageId
→ 单次 Package 唯一标识；可用于 Attempt 绑定

mode
→ APPLY | RECONCILE

sourceRevision
→ 请求 currentTemplateState.templateRevision

currentStateDigest
→ 请求 currentTemplateState 的 canonical digest

nextStateDigest
→ nextTemplateState 的 canonical digest

strategies
→ 最终 Wire Strategy 数组

validationPlan
→ 最终 ValidationPlanItemV2 数组

payloadManifest
→ ZIP payload 的完整 manifest

nextTemplateState
→ 仅在 Validation 成功后由 XCodeAgent 提交的候选 State

diagnostics
→ 非执行事实的诊断信息；不得承载 Workspace 内容
```

### 17.3 Payload Manifest

结构：

```json
{
  "payload/authorization/role-page.tsx": {
    "size": 1234,
    "sha256": "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
  }
}
```

硬约束：

```text
payloadManifest key 集合
==
ZIP 中实际 payload/** 文件集合
```

每个 payload：

```text
size 必须匹配实际 bytes
sha256 必须匹配实际 bytes
```

Strategy 的 `payloadRef` 非空时：

```text
必须存在于 payloadManifest
```

不得存在：

```text
未声明 payload
声明但 ZIP 缺失 payload
payload SHA 不匹配
payload size 不匹配
```

### 17.4 Package Digest

`packageDigest` 不写入 `StrategyUpdatePackageV2`。

XCodeAgent 在下载完成后对**整个 ZIP 原始 bytes**计算：

```text
sha256:<64 lowercase hex>
```

并将其作为 immutable Package / Attempt 恢复事实使用。

### 17.5 RECONCILE Package 不变式

当：

```text
mode = RECONCILE
```

必须满足：

```text
currentStateDigest == nextStateDigest
```

同时 `nextTemplateState` 与 current State 在语义上保持一致。

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
→ Validation
→ appliedAdditions
→ TemplateState V2
→ Generate ZIP
```

Generate ZIP：

```text
frontend/**
backend/**
.xcodeagent/template-state.json
```

其中 `.xcodeagent/template-state.json` 必须严格符合第 3 节 TemplateStateV2。

Generate 与 Update 必须共享：

```text
Capability Definition
StrategyRegistryV2
ValidatorRegistryV2
Release Identity
requested / effective 语义
Addition Identity
```

禁止：

```text
Generate → Legacy Core → Adapter → Fake V2 State
```

### 18.1 空 Capability 场景

即使：

```json
{
  "capabilities": {}
}
```

Generate 也必须成功生成 Base Project + 有效 TemplateStateV2。

不得因为初始 Decision 为 `NO_CHANGE` 而产生空 `nextTemplateState` 或 500。

---

## 19. 双端协议不变式

最终必须同时满足：

```text
Service OpenAPI
=
Service Runtime DTO
=
Service Package Builder
=
XCodeAgent ProtocolV2 Model
=
XCodeAgent Package Validator
```

任何一处 schema 变化必须同步修改：

```text
Template Service Contract Test
+
XCodeAgent Contract Test
+
Cross-repo E2E Test
```

不得只修改其中一端。

---

# 第二章 实施计划

## TS-0：冻结双端协议

在任何新的运行时实现修改之前，先冻结以下协议：

```text
TemplateStateV2
UpdateRequestV2
StrategyDescriptorV2
StrategyTypeV2
ValidationPlanItemV2
PayloadDescriptorV2
StrategyUpdatePackageV2
State Digest Algorithm
ZIP Layout
Error Codes
```

### TS-0.1 OpenAPI 必须与 XCodeAgent 当前协议一致

`/v1/update` Request 必须包含：

```text
protocolVersion = "2"
currentTemplateState
requestedConfig
mode
```

`200` Response 必须声明：

```text
application/zip
→ StrategyUpdatePackageV2 ZIP
```

### TS-0.2 删除旧 Wire Contract 的文档定义

文档与 OpenAPI 不再定义：

```text
manifest.json
modification-strategy.json
next-template-state.json
validation-plan.json
```

作为独立 Wire 元数据文件。

### TS-0.3 Contract Test

必须有固定 fixture 同时被两端接受：

```text
UpdateRequestV2 fixture
TemplateStateV2 fixture
StrategyUpdatePackageV2 fixture
RECONCILE fixture
ADD_FILE payload fixture
```

验收：

```text
JSON Schema / OpenAPI Contract Test 全绿
XCodeAgent Pydantic strict validation 全绿
```

---

## TS-1：建立 V2 Domain Model

保留并收敛：

```text
TemplateStateV2
CapabilityState
AppliedAdditionState
ReconcileReason
ModificationStrategy / DomainStrategy
ValidationPlan
UpdateResult
```

硬约束：

```text
V2 Domain 不得出现 managedFiles
AppliedAddition 不得出现 origin
```

Domain Model 可以与 Wire Model 分离，但必须有唯一的显式 Wire Compiler。

---

## TS-2：建立 Capability Metadata V2 与 Registry

包含：

```text
CapabilityV2Loader
CapabilityRegistryV2
StrategyRegistryV2
ValidatorRegistryV2
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

本轮新增硬约束：

```text
StrategyRegistryV2.type
必须直接是受支持 Wire Type
或存在确定性的静态映射到 Wire Type
```

长期目标：直接使用 Wire Type，清除 `RENDER_EXTENSION / TRANSFORM_FILE` V2 运行时语义。

验收：

```text
duplicate additionId
maintainPolicy missing
NO_OP with strategyId
STRATEGY without strategyId
unknown strategyId
unknown validatorId
unknown wire strategy type
invalid strategy parameters
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
domainStrategies
validation intents
candidate nextTemplateState
```

Mode 硬约束：

```text
APPLY
→ 允许 State 变化

RECONCILE
→ State 必须保持不变
```

测试至少覆盖：

```text
{} → login
{} → authorization
repeat APPLY → NO_CHANGE
repeat RECONCILE → HEALTH_REPAIR
config change
release refresh
removal reject
RECONCILE requiring state change → reject
```

---

## TS-4：实现 Wire Strategy Compiler

原“Modification Strategy Builder”拆成两层：

```text
Metadata / Decision
→ Domain Strategy
→ Wire Strategy Compiler
→ StrategyDescriptorV2
```

### TS-4.1 禁止 Wire `TRANSFORM_FILE`

以下不得进入最终 Package：

```text
TRANSFORM_FILE
RENDER_EXTENSION
```

必须编译为：

```text
ADD_FILE
TEXT_ANCHOR_INSERT
ENSURE_IMPORT
ENSURE_NPM_DEPENDENCY
ENSURE_MAVEN_DEPENDENCY
ENSURE_REACT_PROVIDER
ENSURE_ROUTE
ENSURE_MENU_ITEM
ENSURE_SPRING_BEAN
ENSURE_INTERCEPTOR
```

### TS-4.2 最终排序与 index

Service 先按照：

```text
dependency topology
→ capability stable order
→ strategy.order
→ strategyId
```

排序，再生成：

```text
index = 0..N-1
```

不得把 `order` 作为 Wire 字段返回。

### TS-4.3 测试

必须证明：

```text
Strategy 构建不需要 Workspace 文件内容
相同 State + Config + Release → Wire Strategies 完全一致
未知 Strategy Type → fail-closed
strategyId 重复 → reject
index 非连续 → reject
```

---

## TS-5：实现 Addition CREATE / MAINTAIN

依据：

```text
appliedAdditions
```

生命周期：

```text
不存在 → CREATE
已存在 → MAINTAIN
```

CREATE：

```text
ADD_FILE
+
payloadRef
+
payloadManifest
```

MAINTAIN：

```text
NO_OP
→ 不生成该 Addition Strategy

STRATEGY
→ Registry 指定 strategyId
→ 编译为具体 StrategyTypeV2
```

增加：

```text
ADDITION_IDENTITY_CHANGED
```

验收：

```text
首次 Addition → ADD_FILE
重复 Addition + NO_OP → 无 Addition Strategy
重复 Addition + STRATEGY → 固定 Wire Strategy
maintainPolicy missing → Metadata load fail
STRATEGY missing strategyId → Metadata load fail
same id + different target → reject
ADD_FILE payloadRef 必须存在于 payloadManifest
```

---

## TS-6：实现 Release Refresh / Config Change

支持：

```text
CONFIG_CHANGE
RELEASE_REFRESH
```

Refresh 只重新生成 Strategy，不读取 Workspace。

状态变化只能使用：

```text
mode = APPLY
```

如果 `RECONCILE` 遇到需要 Refresh State 的 Release：

```text
RECONCILE_STATE_CHANGE_REQUIRED
```

---

## TS-7：实现 `/v1/update` V2 Request

Controller 固定：

```text
Validate protocolVersion="2"
→ Parse TemplateStateV2
→ Parse RequestedConfig
→ Parse Mode
→ ReconcileDecisionEngine
→ NO_CHANGE: 204
→ CHANGE: Build StrategyUpdatePackageV2
```

Request 严格只允许：

```text
protocolVersion
currentTemplateState
requestedConfig
mode
```

禁止出现：

```text
workspaceSnapshot
workspaceContext
workspaceFiles
current file content
sha256 of Workspace file
```

验收必须直接使用 XCodeAgent 当前客户端发出的真实 JSON fixture。

---

## TS-8：实现 StrategyUpdatePackageV2 Builder

固定 ZIP：

```text
strategy-update-package.json
payload/**
```

Builder 必须生成：

```text
protocolVersion
packageId
mode
sourceRevision
currentStateDigest
nextStateDigest
strategies
validationPlan
payloadManifest
nextTemplateState
diagnostics
```

### TS-8.1 Payload 完整性

必须先完整生成所有 payload bytes，再计算：

```text
size
sha256
```

然后生成 `payloadManifest`。

### TS-8.2 Package 原子性

```text
全部元数据和 payload 成功
→ HTTP 200

任何一步失败
→ 不返回半包
```

### TS-8.3 ZIP allow-list

最终 ZIP 只能有：

```text
strategy-update-package.json
payload/**
```

禁止残留旧文件：

```text
manifest.json
modification-strategy.json
next-template-state.json
validation-plan.json
change-set.json
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

Generate 也必须使用与 Update 相同的 StrategyRegistryV2 语义。

新增验收：

```text
requestedConfig.capabilities = {}
→ 仍能生成 Base + TemplateStateV2
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

Generate ZIP 必须能被 XCodeAgent `validate_template_package` 直接接受，无 adapter。

---

## TS-11：切换生产 `/v1/update`

生产 Update：

```text
只接受 protocolVersion="2"
只接受 TemplateStateV2
只返回 StrategyUpdatePackageV2
```

当前不兼容旧 State。

旧 State：

```text
TEMPLATE_STATE_SCHEMA_UNSUPPORTED
```

旧 Package Contract 不再兼容：

```text
manifest.json + modification-strategy.json + ...
```

生产链路必须满足：

```text
XCodeAgent TemplateEngineClient.update()
        ↓
Template Service /v1/update
        ↓
StrategyUpdatePackageV2 ZIP
        ↓
XCodeAgent validate_strategy_update_package()
```

中间不得存在协议适配层。

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
Wire Strategy Compiler
```

不进入当前 Update 主链路。

---

## TS-13：Acceptance Matrix

至少覆盖：

```text
Generate empty capability
Generate login
Generate authorization
Generate State can be parsed by XCodeAgent TemplateStateV2
Update Request includes protocolVersion="2"
Update NO_CHANGE → 204
Enable login
Enable authorization
Config Change
Release Refresh via APPLY
RECONCILE state-preserving health repair
RECONCILE requiring State change → reject
Removal reject
Addition CREATE
Addition MAINTAIN + NO_OP
Addition MAINTAIN + STRATEGY
maintainPolicy 缺失拒绝
相同 State + Config + Release 的 Strategy 输出确定性一致
Addition identity changed
Unsupported State Schema
Unsupported protocolVersion
Unsupported Wire Strategy Type
Strategy index non-contiguous reject
Validation index non-contiguous reject
payloadRef missing from manifest reject
payloadManifest missing ZIP payload reject
payload SHA mismatch reject
payload size mismatch reject
nextStateDigest mismatch reject
RECONCILE currentStateDigest != nextStateDigest reject
Update Package build failure
```

### TS-13.1 架构门禁

`/v1/update` Request Schema 不得出现 Workspace 内容字段。

### TS-13.2 Cross-repo Contract Test

必须至少建立一条真实双端 E2E：

```text
Template Service /v1/generate
→ XCodeAgent validate_template_package
→ Bootstrap Workspace
→ XCodeAgent TemplateEngineClient.update
→ Template Service /v1/update
→ XCodeAgent validate_strategy_update_package
→ Strategy Executor
→ Validation
→ Commit TemplateStateV2
```

以下不能算双端验收：

```text
Service MockMvc 自己生成自己解析
XCodeAgent 使用手工构造 Mock ZIP
```

必须至少有一条测试消费**真实 Service 返回包**。

---

## TS-14：清理 Legacy 更新语义

协议切换和 Cross-repo E2E 全绿后，删除生产代码中的：

```text
managedFiles
targetFiles vs managedFiles diff
DELETE_FILE
workspaceSnapshot
workspaceContext
requiredWorkspaceFiles
Service-side current-file Transformer
CorePlanResult Update Package
change-set.json
legacy TemplateState mapper
旧四文件 Update Package builder
```

同时清理 V2 Registry 运行时中的：

```text
RENDER_EXTENSION wire type
TRANSFORM_FILE wire type
```

Architecture Test：

```text
ReconcileDecisionEngine
WireStrategyCompiler
UpdateController
StrategyUpdatePackageV2Builder
```

不得依赖 Workspace Runtime 类和 Legacy Core 更新语义。

最终还必须满足：

```text
Addition CREATE / MAINTAIN
由 State 决定

MAINTAIN 后具体做什么
由 Metadata.maintainPolicy 决定

具体 Wire Strategy Type
由 StrategyRegistryV2 决定

Decision Engine 不包含任何基于 Workspace 运行时猜测的分支
```

---

# 第三章 本轮实施顺序

本轮先完成协议收口，再进行代码重构，顺序固定如下：

```text
P0-1  更新本文档并冻结 StrategyUpdatePackageV2
  ↓
P0-2  更新 Service OpenAPI / JSON Contract
  ↓
P0-3  更新 StrategyRegistryV2，消除 Wire TRANSFORM_FILE / RENDER_EXTENSION
  ↓
P0-4  实现 WireStrategyCompiler
  ↓
P0-5  修改 /v1/update Request，接受 protocolVersion="2"
  ↓
P0-6  重写 Update Package Builder 为单 strategy-update-package.json
  ↓
P0-7  补齐 State Digest / payloadManifest / index / validationPlan
  ↓
P0-8  用 XCodeAgent 当前 parser 做 Cross-repo Contract Test
  ↓
P0-9  打通真实 Generate → Update → Apply → Validation → State Commit
  ↓
P1    补全 Acceptance Matrix
  ↓
P2    清理 Legacy
```

在 P0-8 之前，不以“Service 自己的 MockMvc 集成测试通过”作为 V2 双端已调通的判定标准。

---

# 第四章 最终固定数据流

Template Service：

```text
Config + State + Release
        ↓
Deterministic Decision
        ↓
Deterministic Domain Strategy
        ↓
WireStrategyCompiler
        ↓
StrategyDescriptorV2[]
+
ValidationPlanItemV2[]
+
Candidate TemplateStateV2
+
Payload Manifest
        ↓
StrategyUpdatePackageV2
        ↓
Immutable ZIP
```

XCodeAgent：

```text
Immutable ZIP
        ↓
Strict Package Validation
        ↓
Current State Binding
        ↓
Working Copy Strategy Execution
        ↓
Validation
        ↓
Atomic TemplateStateV2 Commit
        ↓
Attempt Finalize / Roll-forward Recovery
```

最终只有一条 Wire Contract：

```text
StrategyUpdatePackageV2
```

不再存在第二套 Template Service 私有 Update Package 协议。
