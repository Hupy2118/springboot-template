# Spring Boot Template Engine

本仓库在迁移期并行提供 Legacy V2 与 Extension V3 Preview。两个引擎均为无状态计算服务，只读取各自的 Runtime Source 并返回确定性 ZIP；它们不管理调用方工程、数据库、Project 或 ChangeSet 状态机。

## 模块

- `template-source/base/`、`template-source/capabilities/`：Legacy V2 Runtime Source。
- `template-source/code/frontend/`、`template-source/code/backend/`：Extension V3 Preview 的双端 Base + Extension Runtime Source。
- `template-engine/engine-core/`：不依赖 Spring 的模板装载、Capability/Extension Resolve、Materialization 和 Update Planning Core。
- `template-engine/engine-service/`：无状态 HTTP API 和 ZIP Package Builder。本地模式固定监听 Loopback，不做应用层认证。
- `scripts/ci/`：Legacy V2 与 Extension V3 独立 Revision Gate，以及 Base Frontend 构建检查。

## 构建与测试

需要 JDK 11+、Maven 3.9+。Java 编译 target 为 8。

```sh
cd /Users/zhangrongrong/Documents/workspace/springboot-template
mvn -f template-engine/pom.xml -pl engine-service -am package -D maven.test.skip=true
```

该命令会构建可执行 JAR，并运行 Service 集成测试。JAR 位于：

```text
template-engine/engine-service/target/engine-service-1.0.0-SNAPSHOT.jar
```

Template Source 的发布门禁使用当前分支的共同基线执行：

```sh
./scripts/ci/verify-template-release-revision.sh origin/main
./scripts/ci/verify-code-template-release-revision.sh origin/main
```

## 启动 Engine Service

Service 的 Source Root 只能来自部署配置，不能由 HTTP 请求指定。以下命令使用 Engine Service 自己管理的本地配置，监听 `127.0.0.1:18080`。

```sh
cd /Users/zhangrongrong/Documents/workspace/springboot-template

export TEMPLATE_ENGINE_SOURCE_ROOT="$(cd template-source && pwd)"

java -jar template-engine/engine-service/target/engine-service-1.0.0-SNAPSHOT.jar \
  --spring.config.additional-location="file:$(pwd)/template-engine/engine-service/config/application-local.yml"
```

`TEMPLATE_ENGINE_SOURCE_ROOT` 必须是模板源的绝对路径；没有默认相对路径。`application-local.yml` 固定绑定 `127.0.0.1`，仅适用于本机 DevAgent Studio 的辅助 Runtime；它不提供应用层认证。

启动成功后，可用以下 URL 访问：

```text
http://127.0.0.1:18080
```

## 查看请求日志

默认启动日志不会逐条记录 HTTP 请求。推荐开启 Tomcat access log：它记录方法、路径、状态码、响应大小和耗时，但不会记录完整 Request Body。

```sh
java -jar template-engine/engine-service/target/engine-service-1.0.0-SNAPSHOT.jar \
  --spring.config.additional-location="file:$(pwd)/template-engine/engine-service/config/application-local.yml" \
  --logging.level.org.springframework.web.servlet.DispatcherServlet=DEBUG \
  --server.tomcat.accesslog.enabled=true \
  --server.tomcat.basedir=/private/tmp/engine-service-tomcat \
  --server.tomcat.accesslog.directory=logs \
  --server.tomcat.accesslog.prefix=access \
  --server.tomcat.accesslog.suffix=.log \
  --server.tomcat.accesslog.pattern='%t %a "%r" %s %b %D'
```

在另一终端中查看：

```sh
tail -F /private/tmp/engine-service-tomcat/logs/access.*.log
```

Tomcat 默认会在文件名中加入日期，例如 `access.2026-09-09.log`，并且通常在收到第一条 HTTP 请求后才创建该文件。若尚未请求接口，请先调用一次 `/v1/generate`；也可先执行 `ls -la /private/tmp/engine-service-tomcat/logs/` 确认实际文件名。

不要通过通用 HTTP 日志打印完整 `currentTemplateState` 或文件内容：它们可能包含大体积工程内容。

## Postman 本地验证

为每个请求设置：

| Header | Value |
| --- | --- |
| `Content-Type` | `application/json` |

### 1. Generate

`POST http://127.0.0.1:18080/v1/generate`

```json
{
  "requestedConfig": {
    "capabilities": {
      "authorization": {
        "enabled": true,
        "config": {}
      }
    }
  }
}
```

预期 HTTP `200`、`Content-Type: application/zip`。在 Postman 选择 **Send and Download**；ZIP 应包含 `frontend/`、`backend/` 和 `.devagentstudio/template-state.json`。

### 2. Update

`/v1/update` 是无状态接口。先从 Generate ZIP 取出 `.devagentstudio/template-state.json`，将其完整 JSON 作为 `currentTemplateState` 传回：

```json
{
  "protocolVersion": "2",
  "currentTemplateState": {
    "schemaVersion": 2,
    "templateRevision": "...",
    "requested": {},
    "effective": {},
    "appliedAdditions": {}
  },
  "requestedConfig": {
    "capabilities": {
      "login": { "enabled": true, "config": {} }
    }
  },
  "mode": "APPLY"
}
```

当配置改变时，预期 `200 application/zip`，ZIP 仅包含 `strategy-update-package.json` 与可选 `payload/`。将该文件的 `nextTemplateState` 与同一 RequestedConfig 再次提交，预期 `204 No Content`。

完整验收契约见 [docs/REFACTOR.md](docs/REFACTOR.md)。

## Extension V3 Preview

迁移期间接口与源码保持隔离：

| API | Engine | Runtime Source |
| --- | --- | --- |
| `/v1/generate`、`/v1/update` | Legacy V2 | `template-source/base`、`template-source/capabilities` |
| `/v1/generate-next`、`/v1/update-next` | Extension V3 Preview | `template-source/code/frontend`、`template-source/code/backend` |

V3 Semantic Contract 见 [docs/v3.md](docs/v3.md)。HTTP 契约以 OpenAPI 为准，Update ZIP 内部 JSON 以 `template-engine/engine-service/src/main/resources/contracts/v3/` 中的 JSON Schema 为准。V3 Revision 独立使用 `template-source/code/template-revision.txt`。

## Capability Authoring

新增 Capability 的本地工作流是 `./capability init`、开发 `.workbench/<id>/project`、可选 `./capability status`、再执行 `./capability build`。完整约束、Draft 所有权和高级调试命令见 [docs/Capability_Authoring_Guide.md](docs/Capability_Authoring_Guide.md)。
