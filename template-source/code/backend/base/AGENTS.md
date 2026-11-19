# 后端工程开发说明

## 技术栈与命令

保持 Java 8 编译兼容，沿用 Maven、Spring Boot 2.7、MyBatis-Plus、Lombok 及 Bee/TDSQL 基线；版本和插件以 `pom.xml` 为准，不单独升级运行依赖。在 backend 目录执行 `mvn test`、`mvn package`、`mvn spring-boot:run -Dspring-boot.run.profiles=dev`。服务端口与 Profile 以 `src/main/resources/application*.yml` 为准；正式工程构建需要 Bee 私有 Maven 依赖及 TDSQL 驱动。

## 目录与依赖方向

在 `com.cmbchina.backend.<business>/` 按职责放置代码，按需要创建层次，不要求空目录：

- `adapter/web/`：Controller、参数校验和 HTTP 映射，只调用应用服务。
- `application/`：用例编排、Application Service、DTO、Assembler，依赖领域抽象，不直接依赖 Controller、Servlet、Mapper 或 PO。
- `domain/`：实体、值对象、领域服务、错误码和 Repository 接口，不依赖 Spring MVC、MyBatis 或数据库。
- `infrastructure/`：实现领域 Repository，承载 Mapper、PO、外部接口适配；MyBatis XML 位于 `src/main/resources/mapper/<business>/`。

依赖为 Web Adapter → Application → Domain，Infrastructure 实现 Domain 抽象。`common/` 只放跨业务稳定能力；现有 `common/config`、`common/exception`、`common/entity/page` 分别负责公共配置、统一异常与错误码、分页对象。

## 响应、事务与配置

- HTTP 统一使用 Bee 的 `BeeResponseEntity`；业务错误使用 `BizException` 与错误码，由 `common/exception/WebGlobalExceptionHandler` 映射。不要创建旧说明中的 BaseExceptionHandler 或另一套响应包装。
- 事务边界放在应用用例，避免 Controller 管理事务；Repository 隔离存储细节。外部接口由基础设施适配，显式处理超时、失败和异常转换，不把数据库事务当作跨系统原子事务。
- 数据源使用 `bee.persistence.ds1.datasource` 和目标 Profile 的 `datasource.ds1.*`；不要用旧 DB_* 或 `.env.example` 模型覆盖。不得提交真实凭据、Token 或密钥，不记录完整敏感请求或配置。

## 受管边界与维护

保留 `Application.java` 启动入口、公共异常/分页/MyBatis 配置。启用能力拥有其代码、配置 Contribution 和资源；普通业务不得覆盖，不能在 Base 写入某个能力的实现或复制旧中央 Anchor。

本文件是后端唯一 Agent 工程开发说明。若工作区根目录存在 `.devagentstudio/context/codebase-manifest.json`，可用它核对实际架构事实。目录职责、依赖方向或公共约定变化时更新本说明；普通业务类和 DTO 修改不要求改写规范。模板维护时修改 Base 或能力片段，不将组合后的能力章节回写 Base。
