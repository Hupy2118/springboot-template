# Spring Boot Template Engine

本仓库提供 V3 Template Engine 及 React、Spring Boot 双端 Base/Extension 模板，为 XCodeAgent / DevAgent Studio 提供工程生成和能力增量装配。

- `template-source/code/`：唯一 V3 Runtime Source（Base、Extension、Route Contracts、Revision）。
- `template-engine/engine-core/`：模板装载、能力解析、工程组装与更新规划。
- `template-engine/engine-service/`：无状态 `/v1/generate`、`/v1/update` HTTP 服务及 ZIP 打包。
- `scripts/ci/`：Release Revision 和模板验证脚本。

**[项目介绍与前后端模板开发指南](docs/DEVELOPMENT_GUIDE.md)**：统一说明架构、开发环境、Base/Extension 维护、完整命令、XCodeAgent 对接、发布验证及注意事项。

对外契约仍以 `template-engine/engine-service/src/main/resources/openapi/engine-service-v1.yaml` 和 `template-engine/engine-service/src/main/resources/contracts/v3/*.schema.json` 为准。
