# Authorization 后端能力

本文件仅在 Authorization Capability 启用时生成。Authorization 依赖 Login；当前用户身份由 Login 的请求上下文提供。

## 注入内容

- 授权管理 Controller、Application Service、DTO/Assembler、领域实体与 Repository，以及 MyBatis Mapper、PO、XML 和实现类均位于 `com.cmbchina.backend.auth` 下。
- `RequireAnyResource` 可标注在 Controller 类或方法上，声明当前用户至少需要拥有的一个资源键。
- `ResourcePermissionInterceptor` 读取 Login 上下文并执行资源校验；引擎以顺序 200 写入受管 Web MVC 扩展面，且排除模拟登录和 Actuator 路径。
- Schema Asset 被投放至 `docs/auth/sql/ddl.sql`，由 `AuthorizationBootstrapCommand` 显式读取；数据库执行由调用方负责，不由 Template Service 记录或执行。

## 维护边界

资源键、角色、成员关系和权限校验属于本 Capability。业务接口需要权限保护时使用 `RequireAnyResource`，不要自行实现平行的 Cookie、角色或资源校验机制。
