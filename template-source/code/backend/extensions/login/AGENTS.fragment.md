<!-- DEVAGENTSTUDIO:CAPABILITY:login:BEGIN -->
## Login 后端能力

生成器向 `Application.java` 注入 `@EnableAuthClient`（`com.cmb.bee.auth.client.config.EnableAuthClient`），向 POM 注入 `ZA21:bee-starter-auth`。这些 Contribution 由能力维护，业务代码不要移除或重复手工注册。

认证参数来自 Manifest 的 `bee.auth.*` Contribution，引用 `yht.authentication.*`；dev Profile 提供默认占位。以实际 `application*.yml` 为准，参数由调用方定点配置，不提交真实密钥。当前配置不承诺 BEE_AUTH_* 自动覆盖；使用已有显式占位符或调用方配置。

身份上下文由 Bee Auth Client 提供；此能力不生成本地 Mock、JWT 或平行认证拦截器。后续能力可依赖此身份上下文，业务代码不自行伪造身份。
<!-- DEVAGENTSTUDIO:CAPABILITY:login:END -->
