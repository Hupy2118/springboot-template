# Template Engine V2 Contract

V1 file-diff engine、`managedFiles` State、aggregate renderer 和私有 Update ZIP 已完成 Cutover 并删除。

当前权威契约如下：

- HTTP：`template-engine/engine-service/src/main/resources/openapi/engine-service-v1.yaml`（内容版本为 V2）
- 双端实施与验收：`docs/XCodeAgent_Template_Capability_增量更新方案_双端实施计划_断点修复版.md`
- Template Source：`capability-v2.yaml`、`strategy-registry-v2.yaml`、Base Atomic Surface 与 `templateRevision`

Service 只生成 `TemplateStateV2` 和 `StrategyUpdatePackageV2`；它不读取或保存 Workspace，也不执行 Apply、Rollback 或 State Commit。

## Template Release Loader Gate

`CapabilityV2Loader` 在接受 Template Release 前 fail-closed 校验：Capability `requires` 的唯一性、引用存在性与无环图；全部十种 Wire Strategy 的精确参数 allow-list；Base 的 import、anchor 与结构化 AST selector surface；Registry Strategy 是否可由当前 Generate materializer 执行；以及 Migration asset、deployment/consumer path 和已冻结 execution trigger。当前唯一支持的 Migration trigger 为 `AUTHORIZATION_BOOTSTRAP_DDL`。任何不满足条件的 Release 均不得进入 Plan、Generate 或 Update。

`templateRevision` 是 Template Release 的唯一版本标识。`CapabilityV2Loader` 不读取或校验 `template-source/release-digests.yaml`；相同 revision 下的内容身份与 revision 单调递增由发布流程和 CI 治理。TemplateState 的变化以结构化状态比较，Update Package 不使用 state、payload 或 package digest。Bearer Token 认证仍使用配置的 UTF-8 SHA-256 digest。`JSON_STRUCTURE_CHECK` 的正式参数和 postcondition check 均为 `path`、`pointer`、`expected`，`expected` 为非空字符串。
