# Template Engine V2 Contract

V1 file-diff engine、`managedFiles` State、aggregate renderer 和私有 Update ZIP 已完成 Cutover 并删除。

当前权威契约如下：

- HTTP：`template-engine/engine-service/src/main/resources/openapi/engine-service-v1.yaml`（内容版本为 V2）
- 双端实施与验收：`docs/XCodeAgent_Template_Capability_增量更新方案_双端实施计划_断点修复版.md`
- Template Source：`capability-v2.yaml`、`strategy-registry-v2.yaml`、Base Atomic Surface 与 `templateRevision`

Service 只生成 `TemplateStateV2` 和 `StrategyUpdatePackageV2`；它不读取或保存 Workspace，也不执行 Apply、Rollback 或 State Commit。

## Template Release Loader Gate

`CapabilityV2Loader` 在接受 Template Release 前 fail-closed 校验：Capability `requires` 的唯一性、引用存在性与无环图；全部十种 Wire Strategy 的精确参数 allow-list；Base 的 import、anchor 与结构化 AST selector surface；Registry Strategy 是否可由当前 Generate materializer 执行；以及 Migration asset、deployment/consumer path 和已冻结 execution trigger。当前唯一支持的 Migration trigger 为 `AUTHORIZATION_BOOTSTRAP_DDL`。任何不满足条件的 Release 均不得进入 Plan、Generate 或 Update。

发布源必须维护 `template-source/release-digests.yaml`：`templateRevision` 对应的 SHA-256 覆盖除该 manifest 外的所有受管源文件。Revision 相同但内容不同会在加载时被拒绝；digest 不进入 `TemplateStateV2` 或 HTTP/Package 字段。`JSON_STRUCTURE_CHECK` 的正式参数和 postcondition check 均为 `path`、`pointer`、`expected`，`expected` 为非空字符串。
