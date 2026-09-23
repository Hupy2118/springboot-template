# Template Engine V3 Phase 0 契约断点修订

本文用于修订现有 `docs/v3.md`。以下内容为规范性要求，应在任何 V3 Controller、Planner、PackageBuilder 实现之前完成。

---

# 1. Managed Contribution 改为资源级聚合

原设计：

```text
annotation:<capabilityId>:<annotationClass>
maven:<capabilityId>:<groupId>:<artifactId>
```

存在错误。

同一物理 Contribution 可以被多个 Extension 声明，但物理工程中只存在一份：

```text
@EnableAuthClient

ZA21:bee-starter-auth
```

因此：

> Managed Contribution 的身份必须由“物理受管资源”决定，而不能由声明它的 Capability 决定。

删除 `ManagedContributionState.capabilityId`。

新增：

```text
owners: string[]
```

---

# 2. Java Annotation Contribution Key

V3.0 中 `Application.java` target 固定，因此 Resource Key 定义为：

```text
annotation:<annotationClass>
```

例如：

```text
annotation:com.cmb.bee.auth.client.config.EnableAuthClient
```

State：

```json
{
  "type": "JAVA_ANNOTATION",
  "target": "backend/src/main/java/com/cmbchina/backend/Application.java",
  "owners": [
    "authorization",
    "login"
  ],
  "spec": {
    "annotationClass": "com.cmb.bee.auth.client.config.EnableAuthClient"
  },
  "appliedRevision": "2026.09.23.2"
}
```

`owners`：

```text
MUST 非空
MUST 去重
MUST 按 Extension ID 字典序输出
每个 owner MUST 存在于 effective
```

---

# 3. Maven Contribution Key

Resource Key：

```text
maven:<groupId>:<artifactId>
```

例如：

```text
maven:ZA21:bee-starter-auth
```

State：

```json
{
  "type": "MAVEN_DEPENDENCY",
  "target": "backend/pom.xml",
  "owners": [
    "authorization",
    "login"
  ],
  "spec": {
    "groupId": "ZA21",
    "artifactId": "bee-starter-auth",
    "version": null,
    "scope": null
  },
  "appliedRevision": "2026.09.23.2"
}
```

同一 Maven Coordinate：

```text
groupId + artifactId
```

如果被多个 Extension 声明，所有声明的：

```text
version
scope
```

必须完全一致。

否则 Release Loader 必须失败：

```text
500 MAVEN_DEPENDENCY_CONFLICT
```

不得选择其中一个版本。

---

# 4. Contribution 聚合算法

Loader/Resolver 在得到 `effective` 后，必须先形成：

```text
Map<ResourceKey, AggregatedManagedContribution>
```

处理每个 Effective Extension 的 Contribution。

第一次遇到 Resource：

```text
创建 Contribution
owners = [当前 extension]
```

再次遇到同一 Resource：

```text
spec 相同
    → 将 extension 加入 owners

spec 不同
    → Release 加载失败
```

同一个 Extension 自己重复声明同一个 Resource：

```text
TEMPLATE_SOURCE_INVALID
```

完成后：

```text
owners 排序
Resource Key 排序
```

Generate、Update、State 都必须消费这一份 Aggregated Model。

不得分别实现三套去重逻辑。

---

# 5. Generate 的 Contribution 行为

Generate 不再：

```text
按 Extension 生成 Contribution
然后临时去重
```

而是：

```text
Effective Extensions
        ↓
ManagedContributionAggregator
        ↓
Aggregated Contributions
        ↓
Project Materializer
        ↓
TemplateStateV3
```

因此：

```text
物理 Application Annotation 数量 = Aggregated Annotation 数量

物理 Maven Dependency 数量 = Aggregated Maven Contribution 数量
```

State 中也一一对应。

---

# 6. Contribution Diff 必须按 Resource Key 进行

Update Planner 比较：

```text
currentState.managedContributions
                    VS
targetAggregatedContributions
```

不得按 Capability 单独比较。

规则固定如下。

旧不存在、新存在：

```text
ENSURE_*
```

旧存在、新不存在：

```text
REMOVE_*
```

旧存在、新存在且 spec 相同：

```text
不产生文件 Operation
```

即使：

```text
owners 发生变化
```

也不得 REMOVE/ENSURE。

只更新：

```text
nextTemplateState.managedContributions
```

旧存在、新存在但 spec 不同：

```text
REMOVE old
ENSURE new
```

---

# 7. 跨 Extension Owner 变化示例

R1：

```text
Login
    → @EnableAuthClient

Authorization
    → @EnableAuthClient
```

State：

```json
{
  "annotation:com.cmb.bee.auth.client.config.EnableAuthClient": {
    "owners": ["authorization", "login"]
  }
}
```

R2：

```text
Login
    → @EnableAuthClient

Authorization
    → 删除该声明
```

Target Aggregated State：

```json
{
  "annotation:com.cmb.bee.auth.client.config.EnableAuthClient": {
    "owners": ["login"]
  }
}
```

Planner：

```text
MUST NOT REMOVE_JAVA_ANNOTATION
MUST NOT ENSURE_JAVA_ANNOTATION
```

只更新 State：

```text
owners:
["authorization", "login"]
        ↓
["login"]
```

因为物理 Resource 仍被 Login 需要。

只有：

```text
owners 从非空集合变为不存在
```

才允许 REMOVE。

---

# 8. Operation ID 同步修改

因为 Operation 操作的是物理 Resource，所以 Operation ID 不再包含 Capability ID。

Java：

```text
ensure-annotation:<annotationClass>

remove-annotation:<annotationClass>
```

Maven：

```text
ensure-maven:<groupId>:<artifactId>

remove-maven:<groupId>:<artifactId>
```

例如：

```text
ensure-annotation:com.cmb.bee.auth.client.config.EnableAuthClient

ensure-maven:ZA21:bee-starter-auth
```

Operation 本身不得依赖某个 owner。

---

# 9. `appliedRevision` 精确定义

`appliedRevision` 定义为：

> 当前 Managed Contribution State 最后一次被 V3 Planner 确认与当前 Template Release Contract 一致时的 Revision。

它不表示“该 Revision 一定执行过文件写操作”。

因此 Release Refresh 时，即使：

```text
spec 未改变
只有 owners 改变
```

next State 仍写：

```text
appliedRevision = currentReleaseRevision
```

这与普通 Artifact 的：

```text
installedRevision
```

语义不同。

`installedRevision` 一旦文件已经安装，不随 Release Refresh 自动改变。

---

# 10. RECONCILE 与共享 Contribution

如果当前 State：

```text
owners = ["authorization", "login"]
```

而当前 Release：

```text
owners = ["login"]
```

虽然物理 Annotation 不需要修改，但 State Contract 已发生变化。

因此：

```text
RECONCILE
→ 409 RECONCILE_STATE_CHANGE_REQUIRED
```

RECONCILE 不允许偷偷更新 owners。

APPLY：

```text
HTTP 200
operations = []
nextTemplateState 更新 owners
```

---

# 11. Package Apply 的原子性改为 MUST

原文中的：

```text
推荐使用临时目录 / worktree
```

不足以定义“原子应用”。

V3 Contract 正式定义：

> Update Package 的应用必须同时满足 Workspace Atomicity 与 Template State Atomicity。

调用方 Executor MUST 保证：

```text
Package 成功
    → 全部文件变更生效
    → nextTemplateState 最后提交

Package 失败
    → Workspace 恢复到应用前状态
    → TemplateState 保持原值
```

不得允许：

```text
Operation 1 已修改文件
Operation 2 失败
Operation 1 修改继续残留
```

---

# 12. Executor 必须采用事务式 Apply

生产 Executor 的具体实现不由 Template Engine 仓库规定，但行为必须满足：

```text
preflight
    ↓
staging / transaction
    ↓
apply all
    ↓
validate
    ↓
commit workspace
    ↓
commit next state
```

允许实现为：

```text
临时目录
git worktree
copy-on-write workspace
可完整回滚的 transaction
```

但最终语义必须一致。

“不支持回滚，仅保证 State 不推进”不符合 V3 Contract。

---

# 13. Executor Preflight

在修改任何 Workspace 文件之前，Executor MUST 完成：

```text
currentStateDigest 校验

所有 Operation schema 校验

所有 target path 安全校验

所有 payload 是否存在

payload size 校验

payload sha256 校验

Operation index 连续性

operationId 唯一性

Operation type 支持性
```

任何 Preflight 失败：

```text
MUST 0 文件修改
MUST 0 State 修改
```

---

# 14. Apply 期间失败

即使 Preflight 全部通过，实际操作仍可能因为：

```text
ADD_FILE target 已存在

Managed Surface 被用户修改

文件权限

文件系统错误
```

失败。

一旦任一 Operation 失败：

```text
整个 Package Apply 失败
```

Executor MUST 将 Workspace 恢复至：

```text
byte-for-byte 等价于 Apply 前状态
```

并保持：

```text
currentTemplateState
```

不变。

---

# 15. Reference Executor 的定位

Template Engine 仓库只需要实现：

```text
Test-only Reference Executor
```

用于证明 V3 Package Contract 可执行，并验证：

```text
Generate/Update Equivalence
Operation Preconditions
Rollback
Atomicity
Determinism
```

Reference Executor：

```text
MUST 位于 test source
MUST NOT 被生产 Service 调用
MUST NOT 暴露 HTTP Endpoint
```

它不代表 DevAgentStudio 的生产 Executor 实现。

---

# 16. Atomicity 必测场景

构造 Update Package：

```text
Operation 0 成功
Operation 1 故意失败
```

执行前保存：

```text
workspace snapshot
currentTemplateState
```

执行失败后必须断言：

```text
Workspace 与 snapshot 完全一致

TemplateState 完全一致
```

不能只验证 State 没有推进。

---

# 17. Phase 0 首先修改 OpenAPI

当前：

```text
template-engine/engine-service/src/main/resources/openapi/engine-service-v1.yaml
```

仍只有：

```text
/v1/generate
/v1/update
```

且：

```yaml
info:
  version: v2
```

因此 Phase 0 尚未完成。

在任何 V3 Production Java 类实现前，必须先增加：

```text
/v1/generate-next
/v1/update-next
```

并冻结所有 V3 Schema。

---

# 18. OpenAPI info.version

由于一个 OpenAPI 文件同时描述：

```text
Legacy V2
+
V3 Preview
```

Phase 0 将：

```yaml
info:
  version: v2+v3-preview
```

不得改成单纯：

```text
v3
```

以免暗示 V2 已经 Cutover。

正式切换后再单独修改 API 文档版本。

---

# 19. Phase 0 必须定义的 Schema

OpenAPI 至少新增：

```text
GenerateNextRequest

UpdateNextRequest

RequestedConfigV3

CapabilityRequestV3

CapabilityStateV3

TemplateStateV3

InstalledArtifactStateV3

ManagedContributionStateV3

JavaAnnotationSpecV3

MavenDependencySpecV3
```

所有固定对象：

```text
additionalProperties: false
```

`ManagedContributionStateV3` 必须采用新的：

```text
owners: string[]
```

不得再包含：

```text
capabilityId
```

---

# 20. OpenAPI 不描述 ZIP 内部 Package

HTTP OpenAPI 负责：

```text
Request
Response Content-Type
HTTP Status
Error Envelope
```

ZIP 内部：

```text
extension-update-package.json
OperationV3
payloadManifest
```

建议同时新增机器可校验的 JSON Schema：

```text
template-engine/engine-service/src/main/resources/contracts/v3/
├── extension-update-package.schema.json
├── template-state.schema.json
└── operation.schema.json
```

避免把 ZIP 内部 JSON Contract 仅写在 Markdown 中。

这些 Schema 属 V3 Protocol Contract，必须由测试加载校验。

---

# 21. Phase 0 OpenAPI Contract Test

新增：

```text
NextOpenApiContractTest
```

至少验证：

```text
旧 /v1/generate 仍存在
旧 /v1/update 仍存在

/v1/generate-next 存在
/v1/update-next 存在

protocolVersion enum == ["3"]

TemplateStateV3.schemaVersion enum == [3]

所有 V3 固定对象 additionalProperties == false

ManagedContributionStateV3 包含 owners
ManagedContributionStateV3 不包含 capabilityId
```

并继续验证旧 V2 Schema 未被改变。

---

# 22. `docs/REFACTOR.md` 的新职责

当前文件仍标题为：

```text
Template Engine V2 Contract
```

Phase 0 必须调整。

不要把完整 V3 文档再复制进去。

建议改成：

```text
Template Engine Contract Index / Migration Boundary
```

明确三件事：

```text
V2 仍是正式 Legacy Contract

V3 是 generate-next/update-next Preview Contract

V2/V3 在迁移期并存
```

并明确权威来源：

```text
HTTP Wire Contract
→ engine-service-v1.yaml

V2 Semantic Contract
→ 现有 V2 文档

V3 Semantic Contract
→ docs/v3.md

V3 ZIP JSON Contract
→ resources/contracts/v3/*.schema.json
```

避免 `REFACTOR.md` 与 `v3.md` 维护两套相同字段说明。

---

# 23. `AGENTS.md` 必须改为双 Source Model

当前：

```text
template-source/ 是唯一受管模板源
修改 Base / Capability 时同步 base.yaml / capability.yaml
```

在 V3 下已经不再完整正确。

Phase 0 必须改成：

```text
Template Engine 当前存在两套隔离的 Release Source。
```

---

# 24. Legacy V2 Source

V2 Runtime Source：

```text
template-source/base/**
template-source/capabilities/**
template-source/strategy-registry-v2.yaml
template-source/template-revision.txt
```

V2 修改继续遵循：

```text
base.yaml
capability-v2.yaml
strategy-registry-v2.yaml
Legacy revision gate
```

不得因为 V3 改造改变这些规则。

---

# 25. V3 Source

V3 Runtime Source：

```text
template-source/code/frontend/base/**

template-source/code/frontend/extensions/*/extension.yaml
template-source/code/frontend/extensions/*/src/**

template-source/code/backend/base/**

template-source/code/backend/extensions/*/extension.yaml
template-source/code/backend/extensions/*/src/**
template-source/code/backend/extensions/*/docs/**
template-source/code/backend/extensions/*/migrations/**

template-source/code/template-revision.txt
```

V3 不存在：

```text
base.yaml
capability-v3.yaml
strategy-registry-v3.yaml
```

---

# 26. V3 Maintenance-only 文件

以下不是 V3 Engine Runtime Source：

```text
code/frontend/workspace/**
code/frontend/assembly/**
code/frontend/profiles.*

code/backend/workspace/**
code/backend/assembly/**
code/backend/assembly/profiles.yaml

Template Development 文档

frontend 根 maintenance package.json

backend-template / backend-template.cmd
```

这些文件变化：

```text
不得触发 V3 Template Revision bump
```

除非该文件本身同时被正式定义为 Runtime Source。

---

# 27. 两套 Source 不允许隐式同步

修改：

```text
template-source/code/**
```

不得要求：

```text
同步修改旧 template-source/base/**
同步修改旧 template-source/capabilities/**
```

反之亦然。

二者是迁移期：

```text
两个独立 Release Model
```

不是双写模型。

如果一次业务修改确实需要同时更新 V2/V3：

```text
必须明确作为两个独立 Source Change
分别通过两个 Revision Gate
```

---

# 28. Revision Gate 拆分

现有：

```text
verify-template-release-revision.sh
```

继续只负责 Legacy V2。

不得改变它的输入集合来同时涵盖 V3。

新增：

```text
list-code-template-release-inputs.py

verify-code-template-release-revision.py

verify-code-template-release-revision.sh
```

专门负责 V3。

---

# 29. V3 Release Input Collector

新的 Collector 不再读取：

```text
base.yaml
capability-v2.yaml
strategy-registry-v2.yaml
```

而是基于 V3 Source Ownership 直接枚举允许的 Runtime Tree。

它必须覆盖文件：

```text
新增
修改
删除
```

并拒绝：

```text
symlink
越界路径
```

Runtime Source 变化而：

```text
code/template-revision.txt
```

未严格增加时，CI 必须失败。

---

# 30. CI README 同步修改

`scripts/ci/README.md` 必须明确存在：

```text
Legacy V2 Revision Gate

V3 Code Template Revision Gate
```

不能继续用一句：

```text
Template Release revision gate
```

让开发者猜当前修改应该更新哪个 Revision。

---

# 31. 根 README 同步修改

当前根 README 仍把仓库整体描述成单一旧 Engine 模型。

Phase 0 应增加迁移说明：

```text
/v1/generate
/v1/update
    → Legacy V2

/v1/generate-next
/v1/update-next
    → V3 Preview
```

并说明：

```text
template-source/
    Legacy Runtime Source

template-source/code/
    V3 Base + Extension Runtime Source
```

不需要在 README 展开完整 V3 Protocol。

---

# 32. Update Request 错误码必须一一固定

不得再写：

```text
BAD_REQUEST
或
PROTOCOL_VERSION_UNSUPPORTED
```

必须按错误类型唯一映射。

| 场景                                      | HTTP | code                                |
| --------------------------------------- | ---: | ----------------------------------- |
| Request 不是 JSON Object                  |  400 | `BAD_REQUEST`                       |
| 顶层字段缺失                                  |  400 | `BAD_REQUEST`                       |
| 顶层存在额外字段                                |  400 | `BAD_REQUEST`                       |
| `protocolVersion` 缺失或不是 String          |  400 | `BAD_REQUEST`                       |
| `protocolVersion` 是 String 但不是 `"3"`    |  400 | `PROTOCOL_VERSION_UNSUPPORTED`      |
| `currentTemplateState` 缺失/null/非 Object |  400 | `BAD_REQUEST`                       |
| `schemaVersion` 缺失或非 Integer            |  400 | `BAD_REQUEST`                       |
| `schemaVersion` 是 Integer 但不是 `3`       |  400 | `TEMPLATE_STATE_SCHEMA_UNSUPPORTED` |
| State 固定字段缺失/多余                         |  400 | `TEMPLATE_STATE_INVALID`            |
| State 内部结构或 Resource Key 不合法            |  400 | `TEMPLATE_STATE_INVALID`            |
| `requestedConfig` 结构错误                  |  400 | `BAD_REQUEST`                       |
| Capability ID 格式非法                      |  400 | `BAD_REQUEST`                       |
| Capability 不存在于当前 Release               |  400 | `CAPABILITY_UNKNOWN`                |
| `mode` 缺失、类型错误或非 APPLY/RECONCILE        |  400 | `BAD_REQUEST`                       |
| 请求导致已有 Capability Removal               |  409 | `CAPABILITY_REMOVAL_UNSUPPORTED`    |
| RECONCILE 需要 State Change               |  409 | `RECONCILE_STATE_CHANGE_REQUIRED`   |
| Server V3 Template Source 非法            |  500 | `TEMPLATE_SOURCE_INVALID`           |
| 前后端同 ID Extension Contract 不一致          |  500 | `EXTENSION_CONTRACT_MISMATCH`       |
| Maven Coordinate spec 冲突                |  500 | `MAVEN_DEPENDENCY_CONFLICT`         |
| ZIP/序列化/Package 构造失败                    |  500 | `PACKAGE_BUILD_FAILED`              |

新增并冻结：

```text
TEMPLATE_STATE_INVALID
```

不要把 State 语义错误全部折叠成泛化 `BAD_REQUEST`。

---

# 33. TemplateStateV3 输入语义校验

`NextEngineMapper` 解析 State 时至少必须验证：

```text
requested key ⊆ effective key

所有 requested/effective CapabilityState.enabled == true

installedArtifacts key 符合 Artifact ID 规则

installedArtifact.capabilityId ∈ effective

installedArtifact.target 为安全路径

installedRevision 为合法 Revision

managedContribution Resource Key 与 type/spec 一致

managedContribution.owners 非空、唯一、有序

每个 owner ∈ effective

Managed target 为协议允许的固定 target

appliedRevision 为合法 Revision
```

不满足：

```text
400 TEMPLATE_STATE_INVALID
```

---

# 34. 不用当前 Release 反向验证旧 State Dependency Closure

必须特别禁止：

```text
用当前 Release 的 requires
要求 currentState.effective
必须正好等于当前 dependency closure
```

因为 Release Refresh 时依赖图本身可能发生变化，而 Server 没有旧 Release Manifest。

State 只能进行自身一致性校验。

新的：

```text
targetEffective
```

再根据当前 Release 独立计算。

两者差异由 Update Planner 处理。

---

# 35. Revision 倒退必须写死

还需要补一个与 Release Refresh 紧邻的边界：

```text
currentState.templateRevision > currentRelease.revision
```

说明调用方项目来自比当前 Server 更新的 Template Release。

不得把它当作普通：

```text
RELEASE_REFRESH
```

向旧模板倒退。

固定返回：

```text
409 TEMPLATE_RELEASE_DOWNGRADE_UNSUPPORTED
```

Revision 使用：

```text
YYYY.MM.DD.N
```

结构化比较，不使用简单字符串比较。

新增错误码：

```text
TEMPLATE_RELEASE_DOWNGRADE_UNSUPPORTED
```

这同样应在 Phase 0 的 OpenAPI/Error Contract 中冻结。

---

# 36. Phase 0 契约测试

Phase 0 不要求实现 Controller，但必须先具备 Contract Tests。

至少新增：

```text
NextOpenApiContractTest
V3JsonSchemaContractTest
V3SourceBoundaryContractTest
```

验证：

```text
OpenAPI V2 未变化

V3 paths/schema 已完整声明

V3 JSON Schema 可解析

Managed Contribution 使用 resource-level identity

owners 为 required

capabilityId 不再属于 ManagedContributionState

V2/V3 Runtime Source Boundary 不重叠误判

两个 Revision Gate 输入集合正确
```

---

# 37. Phase 0 完成条件

只有以下内容全部落库后，才允许进入：

```text
V3 Core Model
Loader
Materializer
Planner
Controller
```

Phase 0 必须已经完成：

```text
engine-service-v1.yaml V3 Preview Contract

V3 State JSON Schema

V3 Operation JSON Schema

V3 Update Package JSON Schema

全部错误码唯一映射

docs/v3.md 最终语义

docs/REFACTOR.md 双版本边界

AGENTS.md 双 Source 开发规则

README.md V2/V3 接口边界

scripts/ci/README.md 双 Revision Gate 说明

V3 Revision Gate

Phase 0 Contract Tests
```

如果上述任一项尚未完成：

> Codex 不应开始编写 `NextEngineController`、`V3UpdatePlanner` 或 `NextPackageBuilder`。

---

# 38. 修订后的核心不变量

V3 后续实现必须始终满足：

```text
Extension 声明属于 Owner

Managed Contribution 属于物理 Resource

一个 Resource 可以有多个 Owner

只有最后一个 Owner 消失时才 REMOVE

普通 Extension Artifact 是 ADD_ONCE

Managed Surface 可以 Repair/Replace

Package Apply 对 Workspace + State 整体原子

V2/V3 Source、Revision、Protocol 完全隔离

OpenAPI/JSON Schema 先于 Production Implementation
```
