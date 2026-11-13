# Spring Boot Template Engine

本仓库为 XCodeAgent / DevAgent Studio 生成 React + Ant Design/Rsbuild 前端和 Spring Boot/Bee 后端。引擎从 `template-source/code/` 下的 Base 和 Extension 生成工程 ZIP，通过 `/v1/update` 提供 V4 能力增量安装并兼容 V3。工程落盘、更新包执行和 TemplateState 由调用方管理。

## 选择启动方式

| 目标 | 启动方式 | 前置条件 |
| --- | --- | --- |
| 生成工程、验证更新接口 | [默认模板引擎](#1-启动默认模板引擎) | JDK 11+、Maven 3.9+ |
| 同时联调生成接口和数据库资源接口 | [Managed Database 本地组合模式](#2-启动-managed-database-本地组合模式) | JDK、Maven、启用 TLS 的 MySQL 8、三个独立数据库账号 |
| 用公开依赖构建生成工程，联调模拟登录 | [Standalone 模板](#3-启动-standalone-模板) | JDK、Maven、Python 3.9+；运行生成工程时按需准备前端工具链和 MySQL |

以下命令均在仓库根目录执行。引擎编译目标为 Java 8，本地默认监听 `127.0.0.1:18080`。引擎从 JAR 内的 [application.yml](template-engine/engine-service/src/main/resources/application.yml) 加载通用配置，`dev` Profile 追加 [application-dev.yml](template-engine/engine-service/src/main/resources/application-dev.yml) 中的本地配置。端口已占用时，可在启动前执行 `export TEMPLATE_ENGINE_PORT=18082`，并在请求终端使用同一个端口。

## 1. 启动默认模板引擎

默认构建只需公开 Maven 依赖，启动无需数据库。

```sh
mvn -f template-engine/pom.xml -pl engine-service -am clean package
export TEMPLATE_ENGINE_SOURCE_ROOT="$(cd template-source && pwd)"
java -jar template-engine/engine-service/target/engine-service-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=dev
```

`TEMPLATE_ENGINE_SOURCE_ROOT` 必须是指向 `template-source` 的绝对路径，末尾无需加 `/code`。看到 `Started EngineServiceApplication` 后，在另一个终端验证生成接口。

```sh
curl --fail-with-body "http://127.0.0.1:${TEMPLATE_ENGINE_PORT:-18080}/v1/generate" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/zip, application/json' \
  --data '{"requestedConfig":{"capabilities":{}}}' \
  --output generated-project.zip
unzip -t generated-project.zip
```

请求生成 Login 或 Authorization 工程时，在 `requestedConfig.capabilities` 中启用相应能力。Authorization 会自动补齐 Login 依赖。正式生成后端运行需要企业 Bee 依赖和 TDSQL 环境，配置见生成工程内的说明及 [开发指南](docs/DEVELOPMENT_GUIDE.md)。

## 2. 启动 Managed Database 本地组合模式

`local-mock` 在同一个 JAR 中启用 Template Engine 和 Managed Database。Bee 基础设施由本地兼容模块提供，资源注册、建表、权限 Seed 和查询访问真实 MySQL。

### 准备 MySQL

使用启用 TLS 的本地 MySQL 8 实例。在具备建库权限的 SQL 会话中创建管理库和业务库。

```sql
CREATE DATABASE IF NOT EXISTS managed_meta CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
CREATE DATABASE IF NOT EXISTS managed_business CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
```

首次启动前，使用具备建表权限的账号在 `managed_meta` 初始化四张管理表。将 `SCHEMA_SETUP_USER` 设为该账号的用户名，MySQL 客户端会提示输入密码。

```sh
mysql --host="${MANAGED_DB_HOST:-127.0.0.1}" --port="${MANAGED_DB_PORT:-3306}" --ssl-mode=REQUIRED \
  --user="${SCHEMA_SETUP_USER:?请设置建表账号}" --password managed_meta \
  < template-engine/managed-database-core/src/main/resources/db/manual/managed-database-schema.sql
```

在启动终端导出以下环境变量。凭据通过本地环境注入。两个 YAML 文件列出应用支持的环境变量及默认值。

| 环境变量 | 用途 |
| --- | --- |
| `MANAGED_DB_HOST`、`MANAGED_DB_PORT` | 管理库地址，默认 `127.0.0.1:3306` |
| `MANAGED_DB_ADMIN_USER`、`MANAGED_DB_ADMIN_PASSWORD` | 管理库账号，需要读写 `managed_meta` 的管理表 |
| `MANAGED_DB_MOUNT_USER`、`MANAGED_DB_MOUNT_PASSWORD` | 业务挂载账号，需要在 `managed_business` 执行建表、回收及 Seed 所需的操作 |
| `MANAGED_DB_RUNTIME_USER`、`MANAGED_DB_RUNTIME_PASSWORD` | 独立调试账号，按需授予业务表读写权限。使用取密接口时须与挂载账号分开 |
| `MANAGED_DATABASE_SERVICE_KEY` | Managed Database 接口的 Bearer 密钥，健康检查也需要 |

### 构建并启动

```sh
mvn -f template-engine/pom.xml -Plocal-mock -pl engine-service -am clean verify -DskipTests
export TEMPLATE_ENGINE_SOURCE_ROOT="$(cd template-source && pwd)"
java -jar template-engine/engine-service/target/engine-service-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=dev,managed-db
```

在另一个终端注入同一服务密钥，检查 Managed Database 健康接口。

```sh
curl --fail-with-body "http://127.0.0.1:${TEMPLATE_ENGINE_PORT:-18080}/managed-database/health" \
  -H "Authorization: Bearer ${MANAGED_DATABASE_SERVICE_KEY:?请设置服务密钥}"
```

HTTP 200、`returnCode=SUC0000` 和 `body.status=UP` 表示健康接口可用。同一端口也可执行第 1 节的生成请求。验证真实数据库读写时，在空闲端口运行 [组合验收脚本](scripts/ci/verify-combined-local.sh)。脚本自行启动 JAR，只有显式设置 `RUN_MUTATING_INTEGRATION_TESTS=true` 才会执行注册、建表、Seed 和回收，请使用隔离测试库。

企业组合构建使用 `-Pmanaged-enterprise`，需要能解析 ZA21、LT99.09 的企业 Maven 配置和独立部署配置。它与 `local-mock` 互斥。正式 Bee 依赖与响应行为需在企业环境验收。

## 3. 启动 Standalone 模板

Standalone Adapter 适用于本地生成使用公开依赖的工程。先按第 1 节构建默认 JAR，再用 Adapter 启动。切换模式前先停止当前引擎进程。

```sh
unset TEMPLATE_ENGINE_SOURCE_ROOT
export TEMPLATE_ENGINE_STANDALONE_ENABLED=true
./devtools/standalone-adapter/run-engine.sh
```

Adapter 会准备并校验 `.standalone-build/template-source/`，然后启动引擎。用第 1 节的生成请求即可下载工程 ZIP。运行生成工程时，前端按其 `package.json` 安装依赖后执行 `pnpm dev`。含 Login 的 Standalone 后端按 [Adapter 指南](devtools/standalone-adapter/README.md) 配置数据库并启动，默认监听 `127.0.0.1:8080`，模拟回调路径为 `GET /api/sso/login?code=mock-code`。

## 文档与契约

- [开发指南](docs/DEVELOPMENT_GUIDE.md) 涵盖模板结构、维护命令、应用配置和发布验证。
- [Managed Database](template-engine/managed-database-core/README.md) 记录数据库资源接口和部署配置。
- [OpenAPI](template-engine/engine-service/src/main/resources/openapi/engine-service-v1.yaml) 定义引擎 HTTP 契约。[V3 Schema](template-engine/engine-service/src/main/resources/contracts/v3/) 和 [V4 Schema](template-engine/engine-service/src/main/resources/contracts/v4/) 定义 ZIP 内部 JSON。更新包由调用方的操作执行器应用。
- [AGENTS.md](AGENTS.md) 记录协作规则和关键接口的验证要求。
