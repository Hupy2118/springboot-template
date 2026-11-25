# Spring Boot Template Engine

本仓库为 XCodeAgent / DevAgent Studio 生成 React + Ant Design/Rsbuild 前端和 Spring Boot/Bee 后端。引擎从 `template-source/code/` 下的 Base 和 Extension 生成工程 ZIP，通过 `/v1/update` 提供 V4 能力增量安装并兼容 V3。工程落盘、更新包执行和 TemplateState 由调用方管理。

模板、应用更新、白名单和数据库管理由同一个 `engine-service` JAR 启动。默认 Maven 构建包含数据库管理和本地 Bee 兼容依赖，直接启动 JAR 即可注册全部接口。使用数据库接口时需准备 MySQL 和数据库配置，见 [第 2 节](#2-配置-mysql-和数据库管理)。

以下命令均在仓库根目录执行，需要 JDK 11+ 和 Maven 3.9+。Standalone 另需 Python 3.9+。使用 Managed Database 资源接口时需准备启用 TLS 的 MySQL 8。引擎编译目标为 Java 8，本地默认监听 `127.0.0.1:18080`。切换启动方式前先停止当前引擎进程。端口已占用时，可在启动前执行 `export TEMPLATE_ENGINE_PORT=18082`，并在请求终端使用同一个端口。JAR 内的配置见 [application.yml](template-engine/engine-service/src/main/resources/application.yml) 和 [application-dev.yml](template-engine/engine-service/src/main/resources/application-dev.yml)。

## 1. 启动默认模板引擎

默认构建使用公开 Maven 依赖。可先用模板接口验证进程，再配置 MySQL 验证数据库接口。

```sh
mvn -f template-engine/pom.xml -pl engine-service -am clean package
export TEMPLATE_ENGINE_SOURCE_ROOT="$(cd template-source && pwd)"
java -jar template-engine/engine-service/target/engine-service-1.0.0-SNAPSHOT.jar
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

## 2. 配置 MySQL 和数据库管理

数据库管理的资源注册、建表、权限 Seed 和查询访问 MySQL。白名单使用同一 MySQL 实例的 `base` 库，数据库管理使用独立的管理库。

### 准备 MySQL

使用启用 TLS 的本地 MySQL 8 实例。在具备建库权限的 SQL 会话中创建管理库、业务库和白名单使用的 `base` 库。

```sql
CREATE DATABASE IF NOT EXISTS managed_meta CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
CREATE DATABASE IF NOT EXISTS managed_business CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
CREATE DATABASE IF NOT EXISTS `base` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
```

使用具备建表权限的账号在 `managed_meta` 初始化四张管理表。将 `SCHEMA_SETUP_USER` 设为建表账号，MySQL 客户端会提示输入密码。

```sh
mysql --host="${MANAGED_DB_HOST:-127.0.0.1}" --port="${MANAGED_DB_PORT:-3306}" --ssl-mode=REQUIRED \
  --user="${SCHEMA_SETUP_USER:?请设置建表账号}" --password managed_meta \
  < template-engine/engine-service/src/main/resources/db/manual/managed-database-schema.sql
```

使用白名单时，执行建表脚本创建 `base.engine_whitelist`。如果旧白名单表仍在管理库，切换前先确认成员 ID 均在 16 字节以内，再将旧表的 `member_id` 导入新表。新表会生成自增 ID 和创建时间。 [白名单建表脚本](template-engine/engine-service/src/main/resources/db/manual/engine-whitelist-schema.sql)

在启动终端导出以下环境变量。使用上面的建库命令时，将管理库和挂载库分别设为 `managed_meta` 和 `managed_business`。

| 环境变量 | 用途 |
| --- | --- |
| `MANAGED_DB_HOST`、`MANAGED_DB_PORT` | 管理库地址，默认 `127.0.0.1:3306` |
| `MANAGED_DB_ADMIN_SCHEMA` | 管理库名称，默认 `managed_meta` |
| `MANAGED_DB_ADMIN_USER`、`MANAGED_DB_ADMIN_PASSWORD` | 管理库账号与密码 |
| `MANAGED_DB_ADMIN_SSL_MODE` | 管理库 JDBC TLS 模式，默认 `VERIFY_IDENTITY` |
| `MANAGED_DATABASE_SERVICE_KEY` | Managed Database 接口的 Bearer 密钥，健康检查也需要 |

管理库配置绑定到 `spring.datasource`，运行账号需具备管理表读写权限。白名单通过同一个连接池访问 `base.engine_whitelist`，运行账号还需对该表具备 SELECT、INSERT 和 UPDATE 权限。挂载位置和账号通过 `devagent-studio.list` 配置，需具备业务库建表、回收及 Seed 权限。独立调试账号通过 `managed-database.runtime.list` 配置。可通过 `SPRING_CONFIG_ADDITIONAL_LOCATION=file:/绝对路径/本地配置.yml` 提供这些配置。使用 `dev` 时会另外加载 `application-dev.yml`，其中的 `spring.datasource` 可通过 `SPRING_DATASOURCE_*` 环境变量覆盖。

### 构建并启动

```sh
mvn -f template-engine/pom.xml -pl engine-service -am clean package
export TEMPLATE_ENGINE_SOURCE_ROOT="$(cd template-source && pwd)"
java -jar template-engine/engine-service/target/engine-service-1.0.0-SNAPSHOT.jar
```

在另一个终端注入同一服务密钥，检查 Managed Database 健康接口。

```sh
curl --fail-with-body "http://127.0.0.1:${TEMPLATE_ENGINE_PORT:-18080}/managed-database/health" \
  -H "Authorization: Bearer ${MANAGED_DATABASE_SERVICE_KEY:?请设置服务密钥}"
```

HTTP 200、`returnCode=SUC0000` 和 `body.status=UP` 表示健康接口可用。同一端口也可执行第 1 节的生成请求。验证真实数据库读写时，在空闲端口运行 [组合验收脚本](scripts/ci/verify-combined-local.sh)。脚本自行启动 JAR，只有显式设置 `RUN_MUTATING_INTEGRATION_TESTS=true` 才会执行注册、建表、Seed 和回收，请使用隔离测试库。

## 3. 启动 Standalone 模板

Standalone Adapter 适用于本地生成使用公开依赖的工程。使用默认 JAR，生成和更新接口都会使用 Standalone 模板源。

```sh
mvn -f template-engine/pom.xml -pl engine-service -am clean package -DskipTests &&
(
  unset TEMPLATE_ENGINE_SOURCE_ROOT
  export TEMPLATE_ENGINE_STANDALONE_ENABLED=true
  ./devtools/standalone-adapter/run-engine.sh
)
```

Adapter 会准备并校验 `.standalone-build/template-source/`，然后启动引擎。用第 1 节的生成请求即可下载工程 ZIP。运行生成工程时，前端按其 `package.json` 安装依赖后执行 `pnpm dev`。含 Login 的 Standalone 后端按 [Adapter 指南](devtools/standalone-adapter/README.md) 配置数据库并启动，默认监听 `127.0.0.1:8080`，模拟回调路径为 `GET /api/sso/login?code=mock-code`。

Standalone 模式的数据库管理仍使用同一个 Engine Service 进程，按第 2 节配置 MySQL 后即可调用。

## 文档与契约

- [开发指南](docs/DEVELOPMENT_GUIDE.md) 涵盖模板结构、维护命令、应用配置和发布验证。
- [Managed Database](template-engine/engine-service/docs/managed-database.md) 记录数据库资源接口和部署配置。
- [OpenAPI](template-engine/engine-service/src/main/resources/openapi/engine-service-v1.yaml) 定义引擎 HTTP 契约。[V3 Schema](template-engine/engine-service/src/main/resources/contracts/v3/) 和 [V4 Schema](template-engine/engine-service/src/main/resources/contracts/v4/) 定义 ZIP 内部 JSON。更新包由调用方的操作执行器应用。
- [AGENTS.md](AGENTS.md) 记录协作规则和关键接口的验证要求。
