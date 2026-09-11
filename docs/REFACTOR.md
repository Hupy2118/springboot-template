# Template Engine V2 Contract

V1 file-diff engine、`managedFiles` State、aggregate renderer 和私有 Update ZIP 已完成 Cutover 并删除。

当前权威契约如下：

- HTTP：`template-engine/engine-service/src/main/resources/openapi/engine-service-v1.yaml`（内容版本为 V2）
- 双端实施与验收：`docs/XCodeAgent_Template_Capability_增量更新方案_双端实施计划_断点修复版.md`
- Template Source：`capability-v2.yaml`、`strategy-registry-v2.yaml`、Base Atomic Surface 与 `templateRevision`

Service 只生成 `TemplateStateV2` 和 `StrategyUpdatePackageV2`；它不读取或保存 Workspace，也不执行 Apply、Rollback 或 State Commit。
