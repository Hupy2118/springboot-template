# Spring Boot Template Engine

本仓库将固定的 `template-source` 与 Capability 配置解析为可生成的前后端工程。V1 的交付边界是：加载模板源、计算确定性文件变更，并通过无状态 HTTP Service 返回 Plan 或 ZIP Package；它不管理调用方工程、数据库、Project 或 ChangeSet 状态机。

## 模块

- `template-source/`：Base、Login、Authorization 等 Capability 的受管模板源。
- `template-engine/engine-core/`：不依赖 Spring 的模板装载、Capability Resolve、渲染和文件级 Diff Core。
- `template-engine/engine-service/`：无状态 HTTP API、Token/Scope 鉴权和 ZIP Package Builder。
- `validation/`：Stage2/Stage3 的本地验证 Fixture 与脚本。

## 构建与测试

需要 JDK 11+、Maven 3.9+。Java 编译 target 为 8。

```sh
cd /Users/zhangrongrong/Documents/workspace/springboot-template
mvn -f template-engine/pom.xml -pl engine-service -am package
```

该命令会构建可执行 JAR，并运行 Service 集成测试。JAR 位于：

```text
template-engine/engine-service/target/engine-service-1.0.0-SNAPSHOT.jar
```

## 启动 Engine Service

Service 的 Source Root 只能来自部署配置，不能由 HTTP 请求指定。以下命令使用仓库内的 Stage3 本地验收配置，监听 `127.0.0.1:18080`。

```sh
cd /Users/zhangrongrong/Documents/workspace/springboot-template

export STAGE3_SOURCE_ROOT="$(pwd)/template-source"

java -jar template-engine/engine-service/target/engine-service-1.0.0-SNAPSHOT.jar \
  --spring.config.additional-location="file:$(pwd)/validation/stage3/"
```

`STAGE3_SOURCE_ROOT` 是环境变量，值为模板源的绝对路径。验收配置位于 `validation/stage3/application.yml`，其中包含仅供本地使用的两个 Token 的 SHA-256 摘要；不要将明文 Token 写入生产配置。

启动成功后，可用以下 URL 访问：

```text
http://127.0.0.1:18080
```

## 查看请求日志

默认启动日志不会逐条记录 HTTP 请求。推荐开启 Tomcat access log：它记录方法、路径、状态码、响应大小和耗时，但不会记录 Bearer Token 或完整 Request Body。

```sh
java -jar template-engine/engine-service/target/engine-service-1.0.0-SNAPSHOT.jar \
  --spring.config.additional-location="file:$(pwd)/validation/stage3/" \
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

Tomcat 默认会在文件名中加入日期，例如 `access.2026-09-09.log`，并且通常在收到第一条 HTTP 请求后才创建该文件。若尚未请求接口，请先调用一次 `/v1/plan`；也可先执行 `ls -la /private/tmp/engine-service-tomcat/logs/` 确认实际文件名。

不要通过通用 HTTP 日志打印 `Authorization`、完整 `currentTemplateState` 或文件内容：它们可能包含 Token 或大体积工程内容。

## Postman 本地验证

为每个请求设置：

| Header | Value |
| --- | --- |
| `Content-Type` | `application/json` |
| `Authorization` | `Bearer stage3-demo-token` |

### 1. Plan

`POST http://127.0.0.1:18080/v1/plan`

```json
{
  "currentTemplateState": null,
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

预期 HTTP `200`、`kind: "CHANGE"`，以及 `nextTemplateState.templateRevision` 等于当前 `template-source/template-revision.txt`。`effective` 会包含显式启用的 `authorization` 及其依赖 `login`。Plan 响应中的 State 保存受管文件内容，因此响应可能较大。

### 2. Generate

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

预期 HTTP `200`、`Content-Type: application/zip`。在 Postman 选择 **Send and Download**；ZIP 应包含 `frontend/`、`backend/` 和 `.xcodeagent/template-state.json`。

### 3. 鉴权检查

- 不发送 `Authorization` 请求 `/v1/plan`：预期 `401` 和 `UNAUTHORIZED`。
- 将 Token 改为 `stage3-plan-token` 请求 `/v1/generate`：预期 `403` 和 `FORBIDDEN`。

### 4. Update

`/v1/update` 是无状态接口。先从 Generate ZIP 取出 `.xcodeagent/template-state.json`，将其完整 JSON 作为 `currentTemplateState` 传回：

```json
{
  "currentTemplateState": { "templateRevision": "...", "managedFiles": {}, "requested": {}, "effective": {} },
  "requestedConfig": {
    "capabilities": {
      "login": { "enabled": true, "config": {} }
    }
  }
}
```

当配置改变时，预期 `200 application/zip`，ZIP 包含 `change-set.json`、`next-template-state.json` 和 `payload/`。将 `next-template-state.json` 与同一 RequestedConfig 再次提交，预期 `204 No Content`。

完整验收契约见 [docs/REFACTOR.md](docs/REFACTOR.md)。
