# Login 后端能力

本文件仅在 Login Capability 启用时生成。它与 `com.cmbchina.backend.auth` 下的登录代码和 `CapabilityWebMvcConfiguration` 中的 Login 拦截器受管块属于同一能力边界。

## 注入内容

- `POST /api/login/mock` 接收 `memberId` 和 `memberName`，签发 HttpOnly Cookie，并返回当前模拟用户。
- `MockJwtService` 创建和解析本地模拟 JWT；`MockLoginProperties` 使用 `devagentstudio.login.mock-login` 前缀配置 Cookie、签发方、TTL 与密钥。
- `UserWebMvcInterceptor` 从 Cookie 恢复当前用户到 `BaseUserDataThreadHodler`，并在请求结束后清理上下文。
- 引擎将该拦截器以顺序 100 注册到受管 Web MVC 扩展面，且排除 `/api/login/mock`。

## 维护边界

这是本地联调登录能力，不替代生产身份提供方。共享环境必须通过外部配置替换默认模拟密钥；不要把真实密钥写入源码或文档。

登录上下文是 Authorization Capability 的前置条件。业务接口如需当前用户，应使用该能力提供的上下文；不要自行解析 Cookie 或重复注册拦截器。
