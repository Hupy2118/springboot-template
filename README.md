# Spring Boot Template Engine

本仓库提供 React + Ant Design/Rsbuild、Spring Boot/Bee 双端 Base/Extension 模板，为 XCodeAgent / DevAgent Studio 提供工程生成和能力增量安装。采用 V3 Template Source 模型；`/v1/update` 支持 V4 安装协议，保留 V3 兼容入口。

Engine Service 是无状态计算入口：返回工程 ZIP 或更新操作包；工作区 Apply、构建、回滚和 TemplateState 持久化由调用方负责。现有能力为 Login、Authorization，后者自动依赖 Login。

| 入口 | 用途 |
| --- | --- |
| [开发指南](docs/DEVELOPMENT_GUIDE.md) | 架构、双端模板维护、应用配置、协议、启动与发布验证 |
| [AGENTS.md](AGENTS.md) | Agent 协作边界、关键面保护及验证要求 |
| [Standalone Adapter](devtools/standalone-adapter/README.md) | 使用公开依赖和模拟登录进行本地生成/运行验证 |
| [OpenAPI](template-engine/engine-service/src/main/resources/openapi/engine-service-v1.yaml) | HTTP 权威契约 |
| [V3 Schema](template-engine/engine-service/src/main/resources/contracts/v3/) / [V4 Schema](template-engine/engine-service/src/main/resources/contracts/v4/) | ZIP 内部 JSON 权威契约 |

正式 Runtime Source 唯一位于 `template-source/code/`；Core 在 `template-engine/engine-core/`，HTTP 与打包在 `template-engine/engine-service/`，发布门禁在 `scripts/ci/`。Assembly、Workspace 和 Standalone 工具不属于正式 Runtime Release Inputs。

## 启动正式模板引擎

需要 JDK 11+、Maven 3.9+，编译目标为 Java 8。在仓库根目录执行：

```sh
mvn -f template-engine/pom.xml -pl engine-service -am package -DskipTests
export TEMPLATE_ENGINE_SOURCE_ROOT="$(cd template-source && pwd)"
java -jar template-engine/engine-service/target/engine-service-1.0.0-SNAPSHOT.jar \
  --spring.config.additional-location="file:$(pwd)/template-engine/engine-service/config/application-local.yml"
```

服务监听 `127.0.0.1:18080`，无应用层认证；端口可由 `TEMPLATE_ENGINE_PORT` 覆盖。Source Root 必须是绝对路径，指向 `template-source`，不要加 `/code`。正式生成后端依赖企业 Bee 私仓与 TDSQL 环境；引擎自身构建成功不代表生成后端已在该环境验收。

## 使用本地 Standalone 模板

先完成上面的引擎打包，再在新终端或停止已有引擎后执行（另需 Python 3.9+）：

```sh
unset TEMPLATE_ENGINE_SOURCE_ROOT
export TEMPLATE_ENGINE_STANDALONE_ENABLED=true
./devtools/standalone-adapter/run-engine.sh
```

脚本在 `.standalone-build/template-source/` 构造独立模板，使用公开 Maven 依赖、MySQL 和本地模拟登录，不修改正式 Base/Extension。当前正式 Revision 为 `2026.10.30.1`，Standalone 为 `2026.10.30.1001`，分别以 `template-revision.txt` 与 Adapter 的 `release.yaml` 为准。

含 Login 的 Standalone 后端默认使用 local Profile 和 `127.0.0.1:8080`；配置数据库后可调用 `GET /api/sso/login?code=mock-code` 完成模拟回调。前端登出调用本地接口。真实数据库权限、Bootstrap 初始化和 Workspace Executor Apply 需单独验收；完整配置、验证与回退步骤见 [Adapter 指南](devtools/standalone-adapter/README.md)。

## 验证生成接口

引擎启动后，在另一个终端执行：

```sh
curl --fail-with-body http://127.0.0.1:18080/v1/generate \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/zip, application/json' \
  --data '{"requestedConfig":{"capabilities":{}}}' \
  --output generated-project.zip
```

Generate 返回完整工程；V4 Update 仅安装尚未安装的能力，同有效能力集合的 APPLY 返回 204，不升级或卸载已安装能力。Update ZIP 必须交给调用方的操作执行器处理，不能直接解压覆盖工作区。
