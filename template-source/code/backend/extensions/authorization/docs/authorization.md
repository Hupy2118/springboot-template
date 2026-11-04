# Authorization 后端能力

本文件仅在 Authorization Capability 启用时生成。Authorization 依赖 Login；当前用户身份由 Login 的请求上下文提供。

## 注入内容

- 授权管理 Controller、Application Service、DTO/Assembler、领域实体与 Repository，以及 MyBatis Mapper、PO、XML 和实现类均位于 `com.cmbchina.backend.auth` 下。
- `RequireAnyResource` 可标注在 Controller 类或方法上，声明当前用户至少需要拥有的一个资源键。
- `ResourcePermissionInterceptor` 读取 Bee Auth Client 的令牌上下文并执行资源校验；Authorization 自己交付 `WebMvcConfiguration` 注册该拦截器。
- DDL 是 Authorization 专属数据库资产，不属于 docs 文档；交付不代表启用 Flyway/Liquibase 自动迁移。唯一 DDL 为 `sql/authorization/schema.sql`，双平台入口是 `scripts/bootstrap-authorization.sh` / `.ps1`。从任意目录执行脚本会定位 backend 并调用显式 Bootstrap MainClass；不加载旧 .env。先执行 `--dry-run`，去掉该参数才连接数据库。
- 读取工作区 `.devagentstudio/plans/technical-plan.json` 的 confirmed `authorization-manifest.v3` 及 `.devagentstudio/application.json.authorization`。不要求 manifest.enabled；system_admin 映射为 SYSTEM_ADMIN，只新增系统管理资源授权。业务默认角色及授权只校验，不落库。
- 独立解析 Bee/TDSQL `bee.persistence.ds1.datasource` 和 Profile `datasource.ds1.*`，默认 dev，可用 `--profile=` 选择。使用配置中的 TDSQL 驱动，不回退 spring.datasource/MySQL，不隐式使用宿主 DB_*。显式占位符按同名环境变量、合并后的 YAML、占位符默认值依次解析；DB_*、MYSQL_*、SPRING_DATASOURCE_* 仍不读取环境值，五个 datasource.ds1.url/port/schema/username/password 引用映射到 DATASOURCE_DS1_URL/PORT/SCHEMA/USERNAME/PASSWORD，其余名称精确匹配。缺失配置直接失败；保持 Base 和 Extension 既有 YAML 默认值。
- 当前权限 MVC 类是 `com.cmbchina.backend.common.config.WebMvcConfiguration`。权限测试需要 Bee 私有 Maven 依赖。Template Service 不执行数据库操作。
- V4 首次启用以 ADD_FILE 交付资产并保留 ENSURE_CAPABILITY_CONFIG。已安装权限不会自动升级或修补缺失资产，必须另行执行经过所有权检查的安全修复。

## 维护边界

资源键、角色、成员关系和权限校验属于本 Capability。业务接口需要权限保护时使用 `RequireAnyResource`，不要自行实现平行的 Cookie、角色或资源校验机制。

## 关联表主键与存量迁移

自 Template Revision `2026.11.03.1` 起，`role_resource` 与 `role_member` 使用数据库自增 `INT` 主键 `id`。
业务关系仍由 `(role_id, resource_key)` 和 `(role_id, member_id)` 唯一约束标识；接口不暴露关系记录 id，批量插入省略 id，由数据库生成。
Bootstrap 会检查两表的 id 字段、单列主键和自增属性；发现旧结构时拒绝继续，不会自动 ALTER TABLE。

新库直接使用 `sql/authorization/schema.sql`。存量库由调用方先核对实际表结构、备份数据，并按目标 TDSQL 环境评估 DDL 锁表与执行窗口。
仅当两表均没有 id 列和显式主键，且原有业务唯一索引完整时，才适用以下一次性迁移；已迁移的表不要重复执行：

```sql
ALTER TABLE `role_resource`
    ADD COLUMN `id` INT NOT NULL AUTO_INCREMENT COMMENT '自增id标志' FIRST,
    ADD PRIMARY KEY (`id`);
ALTER TABLE `role_member`
    ADD COLUMN `id` INT NOT NULL AUTO_INCREMENT COMMENT '自增id标志' FIRST,
    ADD PRIMARY KEY (`id`);
```

两条 DDL 不构成原子事务；若部分成功，核对后只处理未完成的表。迁移后用 `SHOW CREATE TABLE` 确认 id 定义与原业务唯一索引，
再部署对应代码并执行真实 Bootstrap、角色成员及资源绑定/解绑验收。新增 id 不会修复业务重复关系，也不提供业务排序语义。
V4 不会升级已安装的 Authorization，存量生成工程须由调用方同步相关源码/XML 和数据库迁移。
