# Authorization 后端能力

本文件仅在 Authorization Capability 启用时生成。Authorization 依赖 Login；当前用户身份由 Login 的请求上下文提供。

## 注入内容

- 授权管理 Controller、Application Service、DTO/Assembler、领域实体与 Repository，以及 MyBatis Mapper、PO、XML 和实现类均位于 `com.cmbchina.backend.auth` 下。
- `RequireAnyResource` 可标注在 Controller 类或方法上，声明当前用户至少需要拥有的一个资源键。
- `ResourcePermissionInterceptor` 读取 Bee Auth Client 的令牌上下文并执行资源校验；Authorization 自己交付 `WebMvcConfiguration` 注册该拦截器。
- Schema Asset 投放至 `migrations/001-schema.sql`。`AuthorizationBootstrapCommand` 原样交付，但默认配置读取模型与 Bee/TDSQL 基线不兼容；调用方必须显式提供兼容的数据库参数并负责执行，Template Service 不记录或执行数据库状态。

## 维护边界

资源键、角色、成员关系和权限校验属于本 Capability。业务接口需要权限保护时使用 `RequireAnyResource`，不要自行实现平行的 Cookie、角色或资源校验机制。
