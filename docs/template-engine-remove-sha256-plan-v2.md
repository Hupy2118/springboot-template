# Template Engine 去除 SHA-256 修复方案（修订版）

## 1. 方案目标

本次改造的核心目标不是“整个 Template Engine 禁止使用 SHA-256”，而是：

> **将 Template Engine 的模板版本、状态协议、Authoring Baseline 和 Update Package 从 Content Digest 身份体系中解耦，统一以 `templateRevision` 和结构化状态作为事实来源。**

需要明确区分两类 SHA-256：

### 1.1 本次主改造范围

本次优先移除与模板身份、状态和更新协议相关的 Digest：

- Release Digest；
- TemplateState Digest；
- Workbench Baseline Digest；
- Update Package Current / Next State Digest；
- Payload SHA-256；
- 基于 StateDigest 派生的 Package ID。

这些 Digest 属于 Template Engine 的版本/状态协议设计，可以在不改变模板业务代码的前提下收敛。

### 1.2 不作为默认实施项的 SHA-256

Bearer Token 的 SHA-256 属于**安全鉴权设计**，不等同于模板版本 Digest。

当前工程约束如果明确要求：

```text
Bearer Token → UTF-8 SHA-256 → 与配置的 token-sha256 比对
```

则不得在本次模板版本简化中顺带删除。

`TokenAuthenticator` 是否去除 SHA-256，必须作为独立安全设计决策处理。

---

# 第一章 方案设计

## 2. 改造范围

本次只修改模板服务自身代码：

```text
template-engine/
├── engine-core/
├── engine-service/
└── capability-authoring/
```

以及与 Template Engine 直接相关的：

```text
docs/
validation/
OpenAPI
tests
CI rules
```

本次明确不修改：

```text
template-source/**
```

因此：

- `template-source` 中 JWT / HS256 / HmacSHA256 不处理；
- `template-source` 中业务代码的加密逻辑不处理；
- `template-source/release-digests.yaml` 文件本身可以暂时保留；
- 但 Template Engine 改造完成后不得再依赖该文件完成 Runtime 版本识别。

---

## 3. 权威约束冲突必须先处理

当前实现与工程文档可能仍明确要求：

```text
release digest
token-sha256
```

因此实施前必须先处理“代码改造目标”和“现有权威契约”之间的冲突。

必须同步检查并更新：

```text
docs/REFACTOR.md
OpenAPI
engine-service configuration contract
fixture
integration test
CI / validation scripts
```

原则：

> **不能在代码层删除 Digest，而保留文档、测试或接口契约继续要求 Digest。**

其中：

- Release / State / Package Digest：本次方案明确批准删除；
- Token SHA-256：默认保留，除非另外批准安全设计变更。

---

## 4. 目标版本模型

改造后模板 Release Identity 收敛为：

```text
template-source/template-revision.txt
                │
                ▼
       TemplateRelease.revision
                │
                ▼
   TemplateStateV2.templateRevision
                │
                ▼
     ReconcileDecisionEngine
                │
        ┌───────┴────────┐
        │                │
 revision 相同       revision 不同
        │                │
 capability reconcile   RELEASE_REFRESH
```

定义：

1. `templateRevision` 是 Template Release 的唯一版本号；
2. Runtime 不再使用 Template Source SHA-256 判断 Release Identity；
3. Runtime 不再使用 TemplateState SHA-256 判断状态是否发生变化；
4. `AppliedAdditionState.installedRevision` 继续作为 capability addition 的版本事实；
5. 文件内容是否变化由非 Hash 的内容比较机制处理；
6. “模板内容变化但 revision 未升级”由发布治理和 CI 负责。

---

## 5. 历史 Revision 重用策略

这是去掉 Release Digest 后必须显式补上的规则。

原有 Release Digest 实际承担了一项治理职责：

```text
同一个 templateRevision
不能映射到两份不同的 Template Source 内容
```

删除 Digest 后，这个约束不能完全消失，只是从 Runtime 移到发布流程。

### 5.1 Runtime 行为

Runtime：

```text
只读取 templateRevision
不验证历史 revision 对应的文件内容
```

### 5.2 Publish 行为

`TemplatePublisher` 仍必须至少校验：

```text
newRevision != currentRevision
```

正式发布不允许直接复用当前 revision。

### 5.3 历史 Revision 防重用

推荐放到 CI / Git 发布规则：

```text
如果 template-source/** 发生变化
且 template-revision.txt 未变化
→ CI FAIL
```

正式发布建议明确采用**单调递增 revision**，避免删除 Release Digest 后出现历史 revision 被重新使用。

如果当前格式为：

```text
YYYY.MM.DD.N
```

则发布规则应明确：

```text
newRevision > currentPublishedRevision
```

并至少同时在以下一处强制：

```text
TemplatePublisher
或
Release CI Gate
```

推荐两层都校验：

```text
Publisher → 快速失败，给开发者明确错误
CI        → 作为最终发布门禁
```

禁止：

```text
newRevision == currentRevision
newRevision < currentRevision
复用历史已发布 revision
```

如果存在合法回滚需求，回滚应通过：

```text
发布一个新的、更高 revision
其内容回退到历史版本
```

而不是重新使用旧 revision。

这样 revision 本身才能成为稳定的 Release Identity，而不需要 Content Digest 补充身份语义。

本次不建议为了保留“历史内容身份”再引入其他 Hash。

---

## 6. 文件差异判断：补齐 FileDiffer

删除 SHA-256 后，文件是否发生变化不能退化成“只看 revision”。

需要明确区分：

```text
Release Version 判断
```

和：

```text
具体文件内容是否发生变化
```

### 6.1 FileDiffer 的职责

`FileDiffer` 应负责比较“当前工作区文件内容”和“目标模板文件内容”，不再使用任何摘要值作为变化判定依据。

默认实现必须是**字节级直接比较**：

```java
Arrays.equals(currentBytes, expectedBytes)
```

或语义等价的 byte-by-byte 比较。

实现约束：

1. 二进制文件必须直接比较原始 bytes；
2. 文本文件只有在现有工程已经明确规定 Normalize 语义时，才允许先执行既有 Normalize，再比较 bytes；
3. 不得为了减少内存或追求“快速比较”重新引入 MD5、SHA、CRC 等摘要；
4. 大文件如确有内存问题，应使用流式逐块字节比较，而不是改回 Hash；
5. FileDiffer 的返回结果只表达 `UNCHANGED / CHANGED`，不产生 content identity。

因此推荐抽象为：

```text
FileDiffer.compare(current, expected)
    → UNCHANGED
    → CHANGED
```

而不是：

```text
FileDiffer.digest(file)
```

对于文本文件，如果现有系统定义了：

- 换行规范化；
- EOF 规范化；

则继续沿用现有 Normalize 规则，再做内容比较；不得在本次改造中擅自新增 Normalize 规则。

### 6.2 明确禁止

不得把 FileDiffer 改成：

```text
SHA-1
MD5
SHA-512
CRC
其他 Content Hash
```

FileDiffer 应直接比较实际内容，而不是比较内容摘要。

### 6.3 FileDiffer 与 templateRevision 的关系

两者职责不同：

```text
templateRevision
→ 判断 Release 是否发生版本变化

FileDiffer
→ 判断某个受管文件当前内容与目标内容是否不同
```

不能用 revision 替代 FileDiffer，也不能用 File Hash 替代 revision。

---

## 7. engine-core：删除 Release Digest

重点文件：

```text
template-engine/engine-core/src/main/java/.../CapabilityV2Loader.java
```

删除：

```text
releaseDigest(...)
publishedDigest(...)
SHA-256 MessageDigest
release-digests.yaml Runtime 读取
TEMPLATE_REVISION_REUSED
```

原逻辑：

```text
read template-revision.txt
        ↓
calculate source digest
        ↓
read release-digests.yaml
        ↓
revision/digest match
        ↓
load TemplateRelease
```

修改为：

```text
read template-revision.txt
        ↓
validate revision
        ↓
load strategies
        ↓
load capabilities
        ↓
validate dependency graph / target / migration / validator
        ↓
return TemplateRelease
```

注意：

`release-digests.yaml` 暂时可以继续存在于 `template-source`，但 Runtime 不再消费。

---

## 8. engine-core：删除 StateDigest

删除：

```text
StateDigest.java
StateDigestGoldenTest.java
validation/fixtures/template-state-v2-golden.sha256
```

原：

```text
TemplateState
↓
canonical JSON
↓
SHA-256
↓
compare digest
```

改为：

```text
TemplateState
↓
结构化表示
↓
equals
```

本次优先使用：

```java
EngineMapper.stateV2(current)
    .equals(EngineMapper.stateV2(next))
```

避免为了本次改造扩大领域模型修改。

后续如果领域模型稳定，可再为：

```text
TemplateStateV2
CapabilityState
AppliedAdditionState
```

补齐 `equals/hashCode`。

---

## 9. capability-authoring：Draft Validator 简化

重点文件：

```text
DraftTemplateSourceValidator.java
```

原逻辑：

```text
copy source
↓
draft-validation revision
↓
calculate SHA-256
↓
patch release-digests.yaml
↓
CapabilityV2Loader.load()
```

修改为：

```text
copy source
↓
draft-validation revision
↓
CapabilityV2Loader.load()
```

删除：

- Digest 计算；
- 临时 release-digests 更新；
- Digest 相关 YAML 操作；
- SHA-256 helper。

---

## 10. capability-authoring：TemplatePublisher 简化

重点文件：

```text
TemplatePublisher.java
```

修改后的职责：

```text
validate revision
        ↓
DraftTemplateSourceValidator
        ↓
RoundTripVerifier
        ↓
PublicationGate
        ↓
write template-revision.txt
        ↓
CapabilityV2Loader.load(...)
        ↓
atomic replace
```

删除：

```text
Template Source SHA-256
release-digests.yaml 更新
digest helper
digest assertion
```

保留：

```text
newRevision != currentRevision
```

并根据第 5 章决定是否增加 revision 单调递增检查。

---

## 11. capability-authoring：删除 baselineDigest

重点文件：

```text
WorkbenchInitializer.java
WorkbenchInitializerTest.java
```

原：

```yaml
capabilityId: xxx
requires:
  - authorization
templateRevision: 2026.09.13.1
baselineDigest: sha256:...
```

修改为：

```yaml
capabilityId: xxx
requires:
  - authorization
templateRevision: 2026.09.13.1
```

Capability Authoring 的差异分析继续依赖：

```text
baseline/
project/
    ↓
CapabilityAnalyzer / FileDiffer
```

而不是 `baselineDigest`。

---

## 12. engine-service：Update Package 协议收敛

重点文件：

```text
PackageBuilder.java
OpenAPI / package contract
EngineServiceIT
package consumer
```

删除：

```json
{
  "currentStateDigest": "...",
  "nextStateDigest": "..."
}
```

Payload Manifest 删除：

```json
{
  "sha256": "..."
}
```

### 12.1 packageId

如果 `packageId` 仅由 `StateDigest` 派生，不能直接改成随机 UUID。

原因：

当前行为要求相同输入：

```text
→ 生成完全一致的 Update Package
```

随机 UUID / timestamp 会破坏 deterministic package。

推荐优先级：

1. 如果外部不消费 `packageId`：删除；
2. 如果只是展示/追踪用途：迁移到请求 traceId，而不是 Package 内容；
3. 如果协议必须保留：设计基于稳定业务字段的确定性 ID，但不得重新引入 Content Hash。

---

## 13. Update Package 消费者兼容性

这是实施前必须增加的一项检查。

删除以下字段：

```text
packageId
currentStateDigest
nextStateDigest
payloadManifest[*].sha256
```

可能影响 XCodeAgent 或其他 Package Consumer。

实施前必须搜索：

```text
currentStateDigest
nextStateDigest
payloadManifest
sha256
packageId
```

确认所有消费者。

### 13.1 如果字段无人消费

可以直接删除并更新 Schema / Test。

### 13.2 如果字段已被消费

必须先形成消费者迁移清单，至少记录：

```text
consumer
消费字段
用途
是否用于校验/幂等/追踪
升级版本
上线顺序
回滚策略
```

然后只能选择以下策略之一：

#### 方案 A：同步升级

Template Engine 和全部 XCodeAgent / Consumer 在同一版本窗口升级。

适用于内部系统强一致发布。

要求：

```text
先完成消费者兼容代码
→ 再发布新 Package Producer
```

不得先删除生产端字段，再等待消费者修复。

#### 方案 B：协议过渡

Producer 在一个明确的过渡版本中仍输出旧字段，但：

```text
Consumer 不再依赖旧 Digest 做判断
```

待所有消费者完成迁移后，再在下一个协议版本删除字段。

过渡期必须有明确结束版本，不允许长期双协议漂移。

#### 方案 C：协议版本升级

如果 Update Package 属于稳定协议，则显式执行：

```text
protocolVersion N
→ protocolVersion N+1
```

并在 N+1 中删除 Digest 字段。

Consumer 必须按 `protocolVersion` 明确分支，不允许通过“字段存在/不存在”猜协议版本。

### 13.3 推荐迁移顺序

优先采用：

```text
1. 枚举消费者
2. 消费者先兼容无 Digest / 新协议
3. 跨仓库 E2E 验证通过
4. Producer 删除 Digest
5. 完成旧协议退出
```

如果无法确认所有消费者，禁止直接删除 Package 字段。

### 13.3 验收要求

Template Engine 单仓验收只验证自身 Package Contract、生成结果与协议稳定性，不要求本仓库实现或持有 Workspace Apply / State Persist 能力。

但删除 Package 字段属于跨仓库协议变更，因此发布前必须经过独立的跨仓库 E2E 门槛：

```text
Template Engine Package
        ↓
外部 XCodeAgent / 调用方
        ↓
Workspace Apply
        ↓
调用方 State Persist
```

该链路属于消费者侧能力，只作为跨仓库发布验收，不作为 `template-engine` 本仓库 Service 集成测试的实现目标。

---

## 14. OpenAPI 与协议文档同步

删除 Digest 字段后必须同步：

```text
engine-service OpenAPI
docs/REFACTOR.md
Package JSON Schema（如存在）
validation fixture
example payload
integration tests
```

原则：

```text
代码、契约、测试、文档必须一次性收敛。
```

不能出现：

```text
实现已删除 digest
但 REFACTOR.md 仍要求 digest
```

或者：

```text
OpenAPI 没有字段
但测试 fixture 仍有字段
```

---

## 15. TokenAuthenticator：独立安全决策

### 15.1 默认结论

**本次主改造不修改 TokenAuthenticator。**

如果当前权威约束要求：

```text
token-sha256
```

则继续保持：

```text
Bearer Token
        ↓
UTF-8 SHA-256
        ↓
constant-time compare
```

该 SHA-256 与 Template Release / State Digest 无关。

因此在本次验收时：

```text
template-engine 中允许 TokenAuthenticator 保留 SHA-256
```

### 15.2 如果未来明确要求鉴权也不能使用 SHA-256

必须单独立项为：

```text
Template Engine Authentication Security Change
```

至少需要明确：

- Secret 注入方式；
- Secret 是否进入 Spring Configuration Environment；
- 日志与 actuator 配置脱敏；
- Secret Manager / Vault / KMS 使用方式；
- Token Rotation；
- 多 Token 并存过渡；
- 调用方兼容；
- 配置中心权限；
- 容器环境变量泄露面；
- Crash Dump / Heap Dump 风险；
- 测试环境与生产环境配置隔离。

不能简单把：

```yaml
token-sha256: xxxx
```

机械修改为：

```yaml
token: plaintext
```

然后认为安全性等价。

`MessageDigest.isEqual()` 只解决常量时间比较问题，不能降低明文 Secret 的存储和暴露风险。

---

# 第二章 实施步骤

## 16. 步骤 0：先更新权威契约

在代码修改前，先调整或批准：

```text
docs/REFACTOR.md
相关 Architecture Decision
Package Contract
Release Contract
```

明确：

### 删除的约束

```text
Release Digest
State Digest
Baseline Digest
Payload Digest
Package State Digest
```

### 保留的约束

```text
Bearer Token token-sha256
```

除非另有独立安全审批。

---

## 17. 步骤 1：解除 CapabilityV2Loader Release Digest

修改：

```text
CapabilityV2Loader
CapabilityV2LoaderContractTest
```

删除：

```text
release-digests Runtime lookup
source SHA-256
TEMPLATE_REVISION_REUSED
```

保留所有结构和语义校验。

---

## 18. 步骤 2：补齐 FileDiffer 非 Hash 比较

检查所有当前依赖：

```text
StateDigest
file digest
payload digest
```

进行“是否变化”判断的代码。

文件内容差异统一改为：

```text
normalized current bytes
vs
normalized expected bytes
```

直接比较。

建议测试至少覆盖：

```text
相同内容 → unchanged
单字节变化 → changed
CRLF / LF（按现有规范决定）
EOF normalization
binary file
empty file
```

---

## 19. 步骤 3：删除 StateDigest

删除：

```text
StateDigest.java
StateDigestGoldenTest.java
*.sha256 fixture
```

所有 TemplateState 是否变化判断改为结构化比较。

---

## 20. 步骤 4：改造 capability-authoring

依次修改：

```text
DraftTemplateSourceValidator
TemplatePublisher
WorkbenchInitializer
相关 tests
```

删除：

```text
release digest
draft digest workaround
baselineDigest
```

---

## 21. 步骤 5：改造 PackageBuilder

删除：

```text
currentStateDigest
nextStateDigest
payload sha256
digest-derived packageId
```

保留 deterministic package 行为。

---

## 22. 步骤 6：检查所有 Package Consumer

在 XCodeAgent / Consumer 仓库搜索：

```text
packageId
currentStateDigest
nextStateDigest
payloadManifest.sha256
sha256
```

形成消费者清单。

在删除协议字段前，明确：

```text
无人消费
/ 同步升级
/ 协议版本升级
```

之一。

---

## 23. 步骤 7：同步 OpenAPI / REFACTOR / Fixture

更新：

```text
docs/REFACTOR.md
engine-service OpenAPI
validation fixture
example JSON
test resources
CI scripts
```

保证与代码一致。

---

## 24. 步骤 8：增加 Revision CI Rule

CI 不应简单把整个：

```text
template-source/**
```

都视为会改变 Template Release 的文件范围，否则 README、说明文件或其他非运行时文件变化也可能被错误要求升级 revision。

应先定义 **Template Release Managed Scope**：

> 以 `CapabilityV2Loader`、Template Source Registry、Capability Definition、Strategy Definition、Schema / Migration 等实际进入 Template Release 或影响生成结果的文件为受管范围。

推荐 CI 使用显式 allowlist，例如按当前实际目录落地为：

```text
template-source/base/**
template-source/capabilities/**
template-source/<实际被 Loader 消费的 registry/schema/migration 路径>
```

具体目录必须以当前 Loader 的真实读取路径为准，不允许凭文档猜测。

以下类型默认不作为 revision 触发条件，除非 Runtime 实际消费：

```text
README / docs
release-digests.yaml
纯开发辅助文件
template-revision.txt 自身
```

最终规则：

```text
IF managed template source changed
AND template-revision.txt unchanged
THEN CI FAIL
```

该规则使用：

```text
git diff
```

即可，不需要 Content Hash。

同时建议把受管范围集中维护为一处配置或脚本常量，避免 CI 和 Loader 对“哪些文件属于 Release”产生两套定义。

---

## 25. 步骤 9：TokenAuthenticator 保持现状

本阶段：

```text
TokenAuthenticator
TemplateEngineProperties.tokenSha256
validation authentication config
```

不修改。

在最终代码清理时需要允许：

```text
TokenAuthenticator 中存在 SHA-256
```

不能把“template-engine grep SHA256 必须零结果”作为本次验收条件。

---

# 第三章 测试与验收

## 26. 单元测试

### Loader

验证：

```text
合法 template revision 正常加载
无 release-digests 依赖也可加载
invalid capability/strategy/target/migration 仍拒绝
```

### FileDiffer

验证真实内容比较。

### TemplateState

验证：

```text
相同结构状态 → unchanged
任一状态字段变化 → changed
```

### Authoring

验证：

```text
Draft Validate 无 digest 也能完成
Workbench authoring.yaml 不再有 baselineDigest
Publish 不再修改 release-digests
```

### PackageBuilder

验证：

```text
不再输出 state/payload digest
相同输入仍产生 deterministic package
```

---

## 27. 集成测试

`template-engine` 本仓库集成测试至少验证：

```text
Generate
Update
Release Refresh
Capability Enable
Capability Disable
No Change
Package Contract
Authentication
```

其中 Authentication 行为必须保持现状。

以下能力不属于本仓库 Service 的单侧集成测试职责：

```text
Workspace Apply
State Persist
```

它们应放入跨仓库 E2E 验收，由 XCodeAgent / 实际调用方执行。

---

## 28. 搜索验收

不能再使用简单的：

```bash
grep SHA256 template-engine
```

要求零结果。

因为 TokenAuthenticator 当前仍允许 SHA-256。

推荐分别搜索。

### 28.1 不允许存在的 Template Digest

```bash
grep -Rni \
  --exclude-dir=target \
  --exclude-dir=.git \
  -E 'StateDigest|baselineDigest|currentStateDigest|nextStateDigest|releaseDigest|publishedDigest' \
  template-engine
```

期望：

```text
无结果
```

### 28.2 release-digests Runtime 依赖

```bash
grep -Rni \
  --exclude-dir=target \
  --exclude-dir=.git \
  'release-digests.yaml' \
  template-engine
```

期望：

```text
无 Runtime 代码依赖
```

### 28.3 SHA-256 剩余用途

```bash
grep -Rni \
  --exclude-dir=target \
  --exclude-dir=.git \
  -E 'SHA-256|SHA256|sha256' \
  template-engine
```

逐项确认。

预期允许：

```text
TokenAuthenticator
token-sha256 configuration
authentication tests
```

不允许再出现：

```text
Release Digest
State Digest
Baseline Digest
Payload Digest
Package Digest
```

---

# 第四章 验收分层

## 29. 本仓库单侧验收

`template-engine` 自身必须完成：

```text
Core unit tests
Service integration tests
Package schema / contract tests
deterministic package tests
Release Refresh tests
Capability Reconcile tests
Authentication regression tests
```

本仓库不新增：

```text
Workspace abstraction
Workspace Apply runtime
XCodeAgent state repository
调用方 State Persist
```

不得为了满足验收而把外部调用方职责重新引入 Template Engine。

## 30. 跨仓库 E2E 发布门槛

涉及 Package Contract 删除或变更时，发布前必须执行：

```text
Template Engine
   ↓
真实 XCodeAgent / Consumer
   ↓
Package Parse
   ↓
Workspace Apply
   ↓
Consumer State Persist
   ↓
后续 Reconcile / Refresh
```

该测试可以位于：

```text
XCodeAgent 仓库
独立 integration repository
发布流水线
```

但不要求由 `template-engine` Service 自己实现 Workspace / State 能力。

跨仓库 E2E 是**发布门槛**，不是 Template Engine 内部架构职责。

---

# 第四章 最终责任边界

## 31. Template Engine Runtime 负责

```text
读取 templateRevision
加载 Template Release
计算 Capability Reconcile
比较结构化 TemplateState
比较真实文件内容
生成 deterministic Update Package
```

## 32. Template Engine Runtime 不再负责

```text
Template Source Content Identity Hash
TemplateState Hash
Workbench Baseline Hash
Update Package State Hash
Payload Hash
```

## 33. CI / Publish Governance 负责

```text
template-source 发生变化时强制升级 templateRevision
revision 格式检查
revision 单调性检查（如启用）
```

## 34. Security Layer 负责

```text
Bearer Token Authentication
token-sha256
Secret 管理
```

Security Layer 的 SHA-256 是否移除，不属于本次默认改造范围。

---

# 第五章 Codex 执行约束

```text
1. 本次目标是删除 Template Release / State / Baseline / Package / Payload Digest，
   不是全仓库禁止 SHA-256。

2. 禁止修改 template-source/** 的业务模板代码。

3. TokenAuthenticator 和 token-sha256 默认保持现状；
   未经独立安全设计批准不得改成明文 token。

4. 修改实现前必须同步 docs/REFACTOR.md 中与 Release Digest 冲突的权威约束，
   不允许“代码已删除、权威文档仍要求”的双重标准。

5. CapabilityV2Loader 不再读取或校验 release-digests.yaml。

6. 删除 StateDigest，TemplateState 变化使用结构化对象比较。

7. FileDiffer 必须直接比较真实内容：
   - 默认 Arrays.equals(bytesA, bytesB) 或等价实现；
   - 大文件可流式逐块比较；
   - 不允许替换为 MD5/SHA/CRC 等其他摘要；
   - 不允许擅自增加新的文本 Normalize 语义。

8. CI 的 revision bump 检查只能覆盖 Template Release Managed Scope，
   不得简单用整个 template-source/** 作为触发范围。
   Managed Scope 必须以 Loader/Registry 实际消费路径为准并集中维护。

9. templateRevision 正式发布必须单调递增：
   - 禁止 == current；
   - 禁止 < current；
   - 禁止复用历史已发布 revision；
   - 回滚内容必须发布为新的更高 revision。

10. 删除 baselineDigest。

11. 删除 PackageBuilder 中 currentStateDigest、nextStateDigest、payload sha256。

12. packageId 如果依赖 StateDigest，不允许直接替换为 UUID/timestamp；
    必须保持 Update Package deterministic。

13. 删除 Package 内部字段前必须完成消费者清单。
    如果存在消费者依赖，必须采用：
    - 消费者先兼容再删字段；
    - 明确过渡版本；或
    - protocolVersion 升级。
    禁止 Producer 单侧先删字段。

14. 同步修改 OpenAPI、REFACTOR.md、fixture、tests、CI，
    保证代码、协议、测试和文档一次性收敛。

15. template-engine 本仓库集成测试只验证 Service/Core/Package Contract，
    不要求实现 Workspace Apply 或 State Persist。

16. Workspace Apply / State Persist 属于外部 XCodeAgent/调用方职责，
    仅作为跨仓库 E2E 发布门槛。

17. 跨仓库 E2E 必须验证：
    Template Engine Package
    → Consumer Parse
    → Workspace Apply
    → Consumer State Persist
    → 后续 Reconcile/Refresh 正常。

18. 最终验收允许 TokenAuthenticator 中继续存在 SHA-256；
    但 Template Identity / State / Baseline / Package / Payload 领域不得再存在 SHA-256。
```

---

# 第六章 最终目标

改造后：

```text
Template Release Identity
    =
templateRevision
```

状态判断：

```text
TemplateState
    =
结构化状态比较
```

文件差异：

```text
Managed File Change
    =
真实文件内容比较
```

发布治理：

```text
Template Source Change
    →
CI 强制 Revision 升级
```

安全鉴权：

```text
Bearer Token SHA-256
```

作为独立 Security Contract 保持现状，除非后续单独批准安全设计变更。

这样可以在不扩大 Secret 暴露面的前提下，先完成 Template Engine 版本与状态协议的收敛。
