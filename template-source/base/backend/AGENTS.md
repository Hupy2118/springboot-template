# Backend Development Guide

## Project Context

修改代码前必须参考：

- `docs/project-structure.md`：工程结构、分层职责和依赖方向。
- `.xcodeagent/context/codebase-manifest.json`：当前工程有效架构视图；仅当该文件存在时必须参考。

## Code Change Rules

- 保持 Java 8 兼容，并使用现有 Maven、Spring Boot 2.7、MyBatis-Plus 和 Lombok 基线；不要单独升级其中任一运行时依赖。
- HTTP 接口只负责参数绑定、校验和调用应用服务；统一使用既有 `ResponseEntity` 与 `BaseExceptionHandler` 返回错误，不要在 Controller 中复制异常处理或响应包装逻辑。
- 新增业务能力按 `adapter`、`application`、`domain`、`infrastructure` 分层；下层不得反向依赖 Web、Spring MVC Controller 或应用启动入口。
- 数据访问通过领域 Repository 抽象及其 Infrastructure 实现接入。实体、Mapper、PO 和 XML Mapper 均属于同一个基础设施边界，避免让 Controller 或 Application Service 直接依赖它们。
- 配置只使用 `application.yml` 中已有的环境变量入口。不得提交密码、Token、连接串中的真实凭据或其他密钥；本地示例见 `.env.example`。

## Capability-managed Surfaces

`common/config/CapabilityWebMvcConfiguration.java` 是引擎管理的扩展面。Capability 的拦截器只能通过引擎生成的受管块接入；不要手动修改锚点、受管块或复制 Capability 的共享配置。

Base 不拥有登录、认证、授权、角色、成员、资源或权限 API。启用的 Capability 才拥有其 `auth` 代码和相关数据库资产；普通业务代码不得改写这些 Capability 所拥有的文件。

启用 Capability 后，阅读 `docs/capabilities/<capability>.md`。这些文件由引擎与该 Capability 的代码一同生成；不要在未启用相应 Capability 的工程中预先创建或引用它们。

## Structure Maintenance

当修改改变目录职责、层级依赖、统一响应/异常处理约定，或 Capability 接入方式时，同步更新 `docs/project-structure.md`。普通业务类、接口实现、DTO 或测试的新增和修改不需要更新该文档。
