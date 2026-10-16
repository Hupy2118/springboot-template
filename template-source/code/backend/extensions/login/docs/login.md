# Login 后端能力

本文件仅在 Login Capability 启用时生成。它提供 Bee Auth Client 的启动注解、Maven 依赖和受管认证配置。

## 注入内容

- 引擎向 Application 注入 `@EnableAuthClient`，并向 Maven POM 注入 `ZA21:bee-starter-auth`。
- 认证配置由受管 `application.yml` 贡献提供；客户端标识、密钥和认证中心地址通过 `BEE_AUTH_*` 环境变量覆盖。

## 维护边界

这是认证客户端集成能力，不生成本地模拟登录、JWT 或 Web 拦截器。不要把真实密钥写入源码或文档。

Login 是 Authorization Capability 的前置条件。业务接口的身份上下文由 Bee Auth Client 提供。
