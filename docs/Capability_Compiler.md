# Capability Compiler 简化实施方案

> 适用分支：`template_refactor`  
> 目标：将开发者在完整工程中的能力开发结果，自动转换为符合现有 Template Capability V2 契约的 Capability。  
> 本方案必须服从 `docs/XCodeAgent_Template_Capability_增量更新方案_双端实施计划_断点修复版.md`，不得新增第二套 Runtime Contract。

---

## 1. 核心定位

Capability Compiler 是 **Template Source 的离线生产工具**，不是 Template Runtime。

职责边界：

```text
开发者
  ↓
Authoring Workbench
  ↓
Capability Compiler
  ↓
Template Source
  ↓
Template Engine / Service
  ↓
StrategyUpdatePackageV2
  ↓
XCodeAgent Workspace
```

保持原有契约不变：

```text
Capability Compiler
→ 可以读取 Authoring Workspace

Template Service / ReconcileDecisionEngine
→ 不读取真实 Workspace
```

Authoring 阶段产生的：

```text
CapabilityDraft
FileChange
StrategyDraft
```

仅为内部中间模型，不得成为新的 Runtime DSL。

最终产物仍然只能落入：

```text
Capability Definition
StrategyRegistryV2 Atomic Entry
ValidatorRegistryV2 Atomic Entry
Base Extension Surface Contract
Migration Contract
```

---

## 2. 开发者使用流程

开发新能力，例如：

```text
excel-export requires login
```

执行：

```bash
capability init excel-export --requires login
```

生成：

```text
.workbench/excel-export/
├── baseline/
├── project/
└── authoring.yaml
```

其中：

```text
baseline = Base + requires
project  = baseline 的可编辑副本
```

开发者只需要在 `project` 中正常开发：

```text
新增页面
新增 Controller / Service
增加 import
在 Base 预留 Anchor 中注册能力
```

然后执行：

```bash
capability capture excel-export
capability compile excel-export
capability verify excel-export
```

最终自动生成：

```text
capabilities/excel-export/**
capability-v2.yaml
Atomic Strategy
Validator
Migration Metadata
```

开发者原则上不再手工维护：

```text
additionId
strategyId
managedMarker
capability-v2.yaml
```

---

## 3. V1 支持范围

第一版只处理当前 Runtime 已稳定支持的能力：

```text
新增文件
→ Addition

新增 import
→ ENSURE_IMPORT

Base Anchor 插入
→ TEXT_ANCHOR_INSERT

Migration
→ 显式配置
```

暂不自动生成：

```text
ENSURE_ROUTE
ENSURE_REACT_PROVIDER
ENSURE_MENU_ITEM
ENSURE_INTERCEPTOR
ENSURE_SPRING_BEAN
ENSURE_MAVEN_DEPENDENCY
ENSURE_NPM_DEPENDENCY
```

原则：

```text
Runtime Contract 先支持
→ Capability Compiler 再自动生成
```

当前 NPM dependency 仍按原契约放入：

```text
Base package.json
+
pnpm-lock.yaml
```

---

# 4. 两个 Baseline 必须区分

### Authoring Baseline

用于判断当前 Capability 增加了什么：

```text
Base + requires
```

例如：

```text
authorization baseline
=
Base + login
```

### Surface Baseline

用于判断 ExistingTarget 是否合法：

```text
Pristine Base
```

因此：

```text
某文件存在于 Base + requires
```

并不意味着：

```text
它可以成为 existingTarget
```

只有在 pristine Base 中已存在、且属于 Target Registry 的文件，才能生成 Modification Strategy。

---

# 5. 分阶段实施

## Stage 1：建立 Authoring Module

新增：

```text
template-engine/capability-authoring
```

只依赖：

```text
engine-core
```

同时将现有 Registry 读取能力抽成公共只读组件：

```text
StrategyRegistryLoader
TargetDefinition
StrategyDefinition
ValidatorDefinition
```

不得新增：

```text
surfaces.yaml
第二套 Target Registry
第二套 Strategy Registry
```

### 验收

```text
CapabilityV2Loader 行为不变
login / authorization 正常加载
Registry Loader 测试通过
template-engine 全量测试通过
```

---

## Stage 2：实现 Workbench Init

支持：

```bash
capability init <id> --requires ...
```

生成：

```text
baseline = Base + requires
project  = baseline copy
authoring.yaml
```

`authoring.yaml` 至少保存：

```yaml
capabilityId: excel-export
requires:
  - login
templateRevision: R10
```

### 验收

```text
baseline 正确包含 requires
project 初始等于 baseline
重复 init 不覆盖已有目录
依赖不存在 / cycle 时失败
```

---

## Stage 3：实现文件 Diff

比较：

```text
baseline
vs
project
```

输出：

```text
ADDED
MODIFIED
DELETED
```

使用：

```text
Java NIO + SHA-256
```

忽略：

```text
node_modules
target
dist
.git
.idea
.vscode
```

### 验收

```text
新增 / 修改 / 删除识别正确
相同输入重复执行结果一致
输出顺序稳定
```

---

## Stage 4：实现 Capability Analyzer

建立：

```text
CapabilityDraft
AdditionDraft
StrategyDraft
UnsupportedChange
```

### AddedFileAnalyzer

```text
ADDED
→ Addition
```

默认：

```text
maintainPolicy = NO_OP
```

`additionId` 必须稳定，不使用：

```text
UUID
timestamp
content hash
```

文件 Rename / Move 暂不支持。

---

### ImportAnalyzer

只支持：

```text
新增 import
→ ENSURE_IMPORT
```

不支持：

```text
删除 import
修改 import
```

Target 必须属于 pristine Base Target Registry。

---

### AnchorInsertAnalyzer

只允许修改已注册的 Base Surface。

例如：

```text
// xcodeagent:capability-page-routes
```

开发者插入内容后自动生成：

```text
TEXT_ANCHOR_INSERT
```

`managedMarker` 必须稳定，例如：

```text
<capabilityId>-<targetId>-<semanticName>
```

内容变化时 marker 不得变化。

#### V1 Managed Marker Contract

`TEXT_ANCHOR_INSERT` 的身份固定为 `(capabilityId, targetId, anchorKey)`。`capabilityId` 与 `anchorKey` 均必须匹配 `[a-z0-9][a-z0-9-]*`；`anchorKey` 必须由 Registry 显式登记。

```text
markerTargetId = targetId 中的 "." 替换为 "-"
managedMarker = <capabilityId>-<markerTargetId>-<anchorKey>
```

最终 marker 必须匹配 `[a-z0-9][a-z0-9-]*`，且不得依赖 content、代码顺序、UUID、timestamp 或 hash。同一身份最多生成一个 insertion；不同身份若生成相同 marker，必须以 `MANAGED_MARKER_COLLISION` fail-closed。Release Refresh 改变 insertion content 时必须复用同一 marker。

---

### UnsupportedChange

以下必须失败：

```text
修改非 Surface Base 文件
删除 Base 文件
修改 Anchor
删除 Base import
修改 requires Capability 独占文件并注册 existingTarget
Addition rename / move
无法解释的 Base 修改
```

### 验收

```text
Addition / Import / Anchor 正确识别
UnsupportedChange 存在时禁止 compile
```

---

## Stage 5：Migration 与 Validator

Migration 不通过 `.sql` 文件自动猜测。

必须在 `authoring.yaml` 显式声明：

```yaml
migrations:
  - id: schema
    source: ...
    bootstrapConsumerPath: ...
    executionTrigger: ...
```

硬约束：

```text
deployment target == bootstrapConsumerPath
```

否则：

```text
MIGRATION_CONTRACT_INVALID
```

每个 Capability 必须至少生成：

```text
<capability>.postcondition
```

类型：

```text
CAPABILITY_POSTCONDITION
```

且 `checks` 非空。

可复用：

```text
frontend.build
backend.test
```

### 验收

```text
Migration 未显式声明时不自动生成
consumer path 不一致时失败
缺少 postcondition 时失败
```

---

## Stage 6：实现 Capability Compiler

输入：

```text
CapabilityDraft
```

输出：

```text
capability-v2.yaml
Capability 文件
Strategy Registry Entry
Validator Registry Entry
Migration Metadata
```

硬约束：

```text
1 StrategyDraft
→
1 Atomic Strategy
```

不得生成高阶 Strategy 再由 Runtime 拆分。

Compiler 必须幂等：

```text
相同输入
→ 相同输出
```

并采用临时目录编译后整体提交，避免半成品。

### 验收

```text
重复 compile 不产生重复 Registry Entry
strategyId 稳定
additionId 稳定
生成结果可以被现有 Loader 读取
```

---

## Stage 7：Draft Validation

Authoring 阶段需要验证 Draft Template Source，但不得削弱正式 Release Gate。

禁止：

```java
load(..., skipDigest=true)
```

建议：

```text
DraftTemplateSourceValidator
```

复用现有：

```text
Schema
Registry
Dependency
Surface
Migration
Validator
```

校验逻辑。

正式 Runtime Loader 继续强制：

```text
Release Digest
```

### 验收

```text
Draft 可以在未发布状态验证
Published Loader 永远不能跳过 Digest
```

---

## Stage 8：Round-trip Verify

验证：

```text
Base + requires
+
Generated Capability
        ↓
V2ProjectGenerator
        ↓
Generated Project
```

必须等于：

```text
Workbench Project
```

至少比较：

```text
文件集合
文件内容
```

仅允许统一：

```text
CRLF / LF
EOF newline
```

### 验收

```text
遗漏文件 → fail
遗漏 Strategy → fail
内容不一致 → fail
```

---

## Stage 9：Generate / XCodeAgent 继续实施step6 等价验证

这是发布前核心契约 Gate。

同一：

```text
Base
Additions
Atomic Strategies
```

分别走：

```text
V2ProjectGenerator
```

和：

```text
XCodeAgent Executor V2
```

要求：

```text
Generated Project
==
Executor Applied Project
==
Workbench Expected Project
```

跨端验证只用于测试，不允许让 Java Runtime 依赖 Python Executor。

### 验收

```text
ENSURE_IMPORT 双端结果一致
TEXT_ANCHOR_INSERT 双端结果一致
重复执行保持幂等
```

---

## Stage 10：Publish

只有以下全部通过才能发布：

```text
Draft Validation
Round-trip
Generate / Executor Equivalence
Capability Postcondition
Frontend Build
Backend Test
```

发布流程：

```text
提升 templateRevision
↓
计算 Release Digest
↓
写入 release-digests.yaml
↓
Published Loader 最终校验
```

任何影响 Runtime 的内容变化：

```text
Base
Capability
Strategy
Validator
Migration
lockfile
Surface
```

都必须提升 `templateRevision`。

### 验收

```text
R10 内容未变 → PASS

修改 R10 内容但不升版本
→ TEMPLATE_REVISION_REUSED

修改内容并发布 R11
→ PASS
```

---

# 6. 最终 CLI

V1 保留五个命令即可：

```bash
capability init <id>

capability capture <id>

capability compile <id>

capability verify <id>

template publish
```

职责：

```text
init
→ 建开发环境

capture
→ 分析变更

compile
→ 生成 Template Source

verify
→ Round-trip + Runtime 等价 + Validator

publish
→ Revision + Digest
```

---

# 7. Codex 实施硬约束

Codex 实施时必须遵守：

```text
1. capability-authoring 不进入 Runtime。

2. Template Service 不读取 Workspace。

3. Authoring Baseline = Base + requires。

4. Surface 校验永远基于 pristine Base。

5. 不新增第二套 Target / Strategy / Validator Registry。

6. StrategyDraft 与 Runtime Atomic Strategy 保持 1:1。

7. 无法识别的修改必须 fail-closed。

8. 不允许 raw patch fallback。

9. managedMarker 必须稳定。

10. Addition Identity 发布后不得漂移。

11. Migration 必须显式声明。

12. 每个 Capability 必须有 CAPABILITY_POSTCONDITION。

13. Generate 与 XCodeAgent Executor 必须等价。

14. Published Loader 不得提供 skipDigest。

15. 当前不动态生成 ENSURE_NPM_DEPENDENCY。

16. 不修改 StrategyUpdatePackageV2 Wire Contract。

17. 每阶段完成后运行全量测试。

18. login / authorization 不允许回归。
```

---

# 8. 最终完成标准

Capability Compiler V1 完成必须完整跑通：

```text
capability init
    ↓
开发完整能力
    ↓
capture
    ↓
Addition / Import / Anchor
    ↓
compile
    ↓
Capability + Atomic Registry
    ↓
verify
    ↓
Generate == Workbench
    ↓
Generate == XCodeAgent Executor
    ↓
Postcondition + Build/Test
    ↓
publish
    ↓
New Revision + Release Digest
```

只有该链路全部自动通过，才视为 V1 完成。
