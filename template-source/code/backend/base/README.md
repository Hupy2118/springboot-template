# Base 后端模板

本工程是 Java 8、Spring Boot 2.7 和 MyBatis-Plus 的后端基础骨架，提供统一响应与异常处理、分页对象、CORS 配置、数据库连接配置，以及由模板引擎管理的 Capability 拦截器组合入口。

Base 本身不提供登录、认证、授权、角色、成员、资源或权限接口；这些能力由启用的 Capability 增量加入。

## 本地开发

在 `backend/` 目录执行：

```bash
mvn test
mvn spring-boot:run
```

默认服务端口为 `8080`。数据库配置通过下列环境变量覆盖：`DB_URL`、`DB_USERNAME`、`DB_PASSWORD`。复制或参考 `.env.example` 填写本地环境，不要将真实凭据提交到版本库。

## 代码约定

目录职责、分层规则和 Capability 扩展约定见 [项目结构文档](docs/project-structure.md)。面向自动化协作的修改规则见 [AGENTS.md](AGENTS.md)。

启用的 Capability 会同时生成其后端说明至 `docs/capabilities/`；该目录的内容以当前实际启用的能力为准，不是 Base 的固定功能清单。
