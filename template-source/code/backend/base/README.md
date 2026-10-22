# Base 后端模板

本工程是 Java 8、Spring Boot 2.7 和 MyBatis-Plus 的后端基础骨架，提供统一响应与异常处理、分页对象、CORS 配置、数据库连接配置，以及 Bee/TDSQL 数据源基座。

Base 本身不提供登录、认证、授权、角色、成员、资源或权限接口；这些能力由启用的 Capability 增量加入。

## 本地开发

在 `backend/` 目录执行：

```bash
mvn test
mvn spring-boot:run
```

默认服务端口为 `8080`。1016 数据源在 `application.yml` 的 `bee.persistence.ds1.datasource` 中配置，`application-dev.yml` 提供 `datasource.ds1.*` 的本地值；使用目标 Profile 配置，不依赖旧 DB_* 或 `.env.example`。需要可访问的 Bee 私有 Maven 仓库与 TDSQL 驱动；不要提交真实凭据。

Authorization 启用后才交付 `scripts/bootstrap-authorization.sh` / `.ps1` 和 `sql/authorization/schema.sql`。脚本读取 `.devagentstudio` v3 规划与应用配置，先用 `--dry-run` 检查，实际初始化由调用方执行。权限 MVC 类为 Extension 自有 `com.cmbchina.backend.common.config.WebMvcConfiguration`。

## 代码约定

目录职责、分层规则和 Capability 扩展约定见 [项目结构文档](docs/project-structure.md)。面向自动化协作的修改规则见 [AGENTS.md](AGENTS.md)。

启用的 Capability 会同时生成其后端说明至 `docs/`；该目录的内容以当前实际启用的能力为准，不是 Base 的固定功能清单。
