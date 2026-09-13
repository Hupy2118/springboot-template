# Capability Authoring 本地开发指南

本文面向在本仓库中新增 Template Capability 的开发者。Capability Compiler 是 Template Source 的离线生产工具；Template Engine / Service 不读取 Workbench，也不保存其状态。

## 1. 先决条件

- 在 `template_refactor` 分支工作。
- 使用 Java 8 兼容代码；通过 Maven 构建。
- 不直接手工编辑生成的 `additionId`、`strategyId`、`managedMarker` 或 `capability-v2.yaml`。
- `template-source/` 是唯一受管模板源。编译会修改它，因此先在干净工作区检查 diff，并为能力单独建分支。

当前 CLI 主类为：

```text
com.xcodeagent.template.authoring.CapabilityCli
```

仓库暂未提供安装到 PATH 的 `capability` 包装脚本；下文的 `capability ...` 表示该 CLI 的命令语义。可由 IDE、Maven Exec 或本地包装脚本调用该主类。

## 2. 标准开发流程

```text
init → 在 project 中开发 → capture → compile → verify → 外部 Release Gate → publish
```

### 2.1 初始化 Workbench

```bash
capability init excel-export --requires login
```

生成目录：

```text
.workbench/excel-export/
├── baseline/       # Base + 传递 requires，禁止手工修改
├── project/        # 唯一允许开发的目录
└── authoring.yaml  # 能力元数据与显式 migration 声明
```

`authoring.yaml` 记录 capability ID、直接依赖、模板 revision 与 baseline digest。重复 `init` 不覆盖已有 Workbench。

### 2.2 在 `project/` 中开发

V1 自动识别的修改只有三类：

| 开发行为 | 生成物 |
| --- | --- |
| 新增文件 | Addition（`NO_OP` maintain policy） |
| 已注册 Surface 中新增 import | `ENSURE_IMPORT` |
| 在已注册 anchor 前插入代码 | `TEXT_ANCHOR_INSERT` |

可插入的位置以 `template-source/strategy-registry-v2.yaml` 的 `anchors` 为准。每个 anchor 都有稳定 `anchorKey`；不要自行复制、删除或改写 Base anchor。

不支持且会 fail-closed 的行为包括：删除 Base 文件、修改非 Surface Base 文件、删除/修改已有 import、改写 anchor、文件 rename/move，以及无法归并到单个 insertion block 的修改。

### 2.3 显式声明 Migration

不要根据 `.sql` 文件名期待自动识别 Migration。需要时在 `.workbench/<id>/authoring.yaml` 加入：

```yaml
migrations:
  - id: schema
    source: db/schema.sql
    bootstrapConsumerPath: backend/db/schema.sql
    executionTrigger: AUTHORIZATION_BOOTSTRAP_DDL
```

可选 `target` 若填写，必须与 `bootstrapConsumerPath` 完全相同；否则会报 `MIGRATION_CONTRACT_INVALID`。`source` 必须是 `project/` 中真实存在的文件。

### 2.4 Capture、Compile、Verify

```bash
capability capture excel-export
capability compile excel-export
capability verify excel-export
```

- `capture` 对比 `baseline/` 与 `project/`，并生成内部 Draft；不支持的修改会拒绝后续编译。
- `compile` 将 Draft 原子化写入 `template-source/`：Capability 文件、Registry Strategy、Validator 与 migration metadata。相同 capability ID 不能重复编译。
- `verify` 使用临时副本写入仅用于验证的 digest，运行正式 Loader，再比较 `V2ProjectGenerator` 输出与 Workbench `project/`。

Round-trip 比较文件集合与文件内容；仅接受 CRLF/LF 与末尾换行差异。

## 3. Marker 与 Anchor 规则

`TEXT_ANCHOR_INSERT` 身份为：

```text
(capabilityId, targetId, anchorKey)
```

```text
markerTargetId = targetId 中的 "." 替换为 "-"
managedMarker = <capabilityId>-<markerTargetId>-<anchorKey>
```

- `capabilityId` 与 `anchorKey` 必须匹配 `[a-z0-9][a-z0-9-]*`。
- marker 不依赖内容、顺序、UUID、timestamp 或 hash。
- 同一身份最多一个 insertion block；不同身份产生相同 marker 时为 `MANAGED_MARKER_COLLISION`。
- 修改已生成 insertion 内容时，必须保留同一 marker。

## 4. 本地验证清单

每次能力开发至少执行：

```bash
mvn -f template-engine/pom.xml -pl capability-authoring -am test
./validation/verify-stage2.sh
git diff --check
```

涉及 Service、HTTP、ZIP 或启动配置时，按仓库 `AGENTS.md` 再执行 Service package 与 Stage 3 HTTP 验收。

建议在 `compile` 后检查：

```bash
git diff -- template-source
```

确认只出现目标 Capability、Registry、Validator、必要 migration 文件与发布元数据的预期改动。

## 5. 发布边界

`template publish` 尚未提供可独立执行的本地 CLI 命令。发布必须由 CI/Release Gate 统一执行，且同时要求：

1. Draft Validation；
2. Round-trip Verify；
3. Template V2 跨端 Contract Test（Java Generator 与 XCodeAgent Executor V2）；
4. Capability postcondition、前端 build、后端 test；
5. 新 `templateRevision` 与对应 `release-digests.yaml` digest；
6. 正式 `CapabilityV2Loader` 校验通过。

跨端 Contract Test 不属于 Capability Compiler Runtime；不得以另一份 Java 代码或跳过 digest 来替代 XCodeAgent Executor 验证。

## 6. 常见失败处理

| 错误 | 处理方式 |
| --- | --- |
| `CAPABILITY_CAPTURE_UNSUPPORTED` | 检查是否修改了未注册 Surface、删除/改写 Base 内容或产生无法归并的 anchor 插入。 |
| `AMBIGUOUS_ANCHOR_INSERT` | 将改动收敛为单一已注册 anchor 的一个 insertion block。 |
| `MANAGED_MARKER_COLLISION` | 调整 Registry target/anchor 设计；不要手工修改 marker 规避冲突。 |
| `MIGRATION_CONTRACT_INVALID` | 显式配置 migration，确保 source 存在且 target 等于 consumer path。 |
| `CAPABILITY_POSTCONDITION_MISSING` | 至少新增一个可检查的文件或受管 anchor block。 |
| `ROUND_TRIP_MISMATCH` | 对照生成项目与 Workbench，检查遗漏 Addition、import、anchor 内容或依赖。 |
| `TEMPLATE_REVISION_REUSED` | 发布时递增 revision，并重新计算/登记 digest；不得修改已有 revision 内容。 |

## 7. 维护原则

- Runtime Contract 先稳定，Compiler 才可自动生成相应 Strategy。
- 不要在 Engine Service 以 capability ID 写分支。
- 不要引入第二套 Target、Strategy 或 Validator Registry。
- 无法解释的变更永远 fail-closed；禁止 raw patch fallback。
- 将 Workbench 视为本地开发输入，最终受管产物始终是 `template-source/`。
