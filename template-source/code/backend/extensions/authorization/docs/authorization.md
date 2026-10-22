# Authorization 后端能力

本文件仅在 Authorization Capability 启用时生成。Authorization 依赖 Login；当前用户身份由 Login 的请求上下文提供。

## 注入内容

- 授权管理 Controller、Application Service、DTO/Assembler、领域实体与 Repository，以及 MyBatis Mapper、PO、XML 和实现类均位于 `com.cmbchina.backend.auth` 下。
- `RequireAnyResource` 可标注在 Controller 类或方法上，声明当前用户至少需要拥有的一个资源键。
- `ResourcePermissionInterceptor` 读取 Bee Auth Client 的令牌上下文并执行资源校验；Authorization 自己交付 `WebMvcConfiguration` 注册该拦截器。
- DDL 是 Authorization 专属数据库资产，不属于 docs 文档；交付不代表启用 Flyway/Liquibase 自动迁移。唯一 DDL 为 `sql/authorization/schema.sql`，双平台入口是 `scripts/bootstrap-authorization.sh` / `.ps1`。从任意目录执行脚本会定位 backend 并调用显式 Bootstrap MainClass；不加载旧 .env。先执行 `--dry-run`，去掉该参数才连接数据库。
- 读取工作区 `.devagentstudio/plans/technical-plan.json` 的 confirmed `authorization-manifest.v3` 及 `.devagentstudio/application.json.authorization`。不要求 manifest.enabled；system_admin 映射为 SYSTEM_ADMIN，只新增系统管理资源授权。业务默认角色及授权只校验，不落库。
- 独立解析 Bee/TDSQL `bee.persistence.ds1.datasource` 和 Profile `datasource.ds1.*`，默认 dev，可用 `--profile=` 选择。使用配置中的 TDSQL 驱动，不回退 spring.datasource/MySQL，不隐式使用宿主 DB_*。缺失配置直接失败；保持 Base 和 Extension 既有 YAML 默认值。
- 当前权限 MVC 类是 `com.cmbchina.backend.common.config.WebMvcConfiguration`。权限测试需要 Bee 私有 Maven 依赖。Template Service 不执行数据库操作。
- V4 首次启用以 ADD_FILE 交付资产并保留 ENSURE_CAPABILITY_CONFIG。已安装权限不会自动升级或修补缺失资产，必须另行执行经过所有权检查的安全修复。

## 维护边界

资源键、角色、成员关系和权限校验属于本 Capability。业务接口需要权限保护时使用 `RequireAnyResource`，不要自行实现平行的 Cookie、角色或资源校验机制。
