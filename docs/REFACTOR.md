# Template Engine V3 收敛记录与契约索引

Template Engine 已收敛为单一 V3 实现。`template-source/code/` 是唯一 Runtime Source；`template-source/code/template-revision.txt` 是唯一 Release Revision。

## Runtime 与服务边界

- Core 仅保留 `core/v3`、`source/v3` 及 `core/common/StateDigest`。Digest 的递归排序、JSON 序列化与 SHA-256 格式保持不变。
- Service 只注册 `CodeTemplateRelease`、`ExtensionResolver`、`V3ProjectMaterializer` 与 `V3UpdatePlanner`，并且只从 `<source-root>/code` 装载 Release。
- Service 是无状态计算入口；Workspace Apply、回滚、构建、测试和 State 提交均由调用方负责。
- `V3UpdatePlanner` 的 Release Refresh 行为及 Update Package 的 `validationPlan` 语义保持原样：V3.0 中 `validationPlan` 与 `diagnostics` 固定为空数组。

## HTTP 与 Package 契约

仅公开以下接口：

- `POST /v1/generate`：V3 RequestedConfig，返回完整工程 ZIP 和 V3 TemplateState。
- `POST /v1/update`：`protocolVersion` 必须为字符串 `"3"`；使用 V3 TemplateState，返回 V3 Update Package，或在无变化时返回 `204`。

旧协议不兼容：`protocolVersion: "2"` 返回 `PROTOCOL_VERSION_UNSUPPORTED`；`schemaVersion: 2` 返回 `TEMPLATE_STATE_SCHEMA_UNSUPPORTED`。不存在自动 State 转换或兼容端点。

OpenAPI 是 HTTP 契约的唯一权威：`template-engine/engine-service/src/main/resources/openapi/engine-service-v1.yaml`。ZIP JSON 契约位于 `template-engine/engine-service/src/main/resources/contracts/v3/`。

## 维护与验证

Base 和 Extension 只在 `template-source/code/frontend/` 和 `template-source/code/backend/` 中维护。Assembly、workspace、profiles 与维护文档不属于 Runtime Release 输入。

完成 Source 或 Core 改动后执行：

```sh
mvn -f template-engine/pom.xml verify
./scripts/ci/verify-code-template-release-revision.sh <base-ref>
```

完成 Service 改动后至少执行：

```sh
mvn -f template-engine/pom.xml -pl engine-service -am package
git diff --check
```

涉及 HTTP、ZIP 或启动配置时，还应使用 `template-engine/engine-service/config/application-local.yml` 和绝对 `TEMPLATE_ENGINE_SOURCE_ROOT` 进行真实 JAR 验收。服务固定监听 `127.0.0.1`。
