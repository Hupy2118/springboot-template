# Authorization Capability

## 职责与配置

Authorization 提供角色、成员、资源、接口权限校验、权限 API、授权管理页、权限 Provider、路由守卫和菜单过滤。它 `required` 依赖 Login，因为所有权限判断均从 Login 提供的当前用户上下文读取成员标识。

配置项只有 `enabled`，默认 `true`。当前内置 Capability 所需的 `ahooks` 已预置到 Base 的 `package.json` 与 `pnpm-lock.yaml`；本 Capability 不拼接或修改它们。

## 文件归属与前端接入

本 Capability 拥有授权 API/类型、资源键、`AuthProvider`、权限 Hook/组件、受保护路由、授权管理页以及后端 auth 实现和 Mapper。它不拥有 Base 的 `App.tsx`、主路由、布局、通用常量或通用路由类型。

它按顺序 200 贡献 `frontend.providers`、`frontend.page-routes`、`frontend.page-wrappers`、`frontend.menu-hooks` 与 `backend.spring-interceptors`。组合结果由受管 `generated/` 文件生成；不得通过手工修改 Base 共享文件接入授权能力。

## 迁移

V2 只登记 `001-schema.sql` 为 Schema Asset。Template Service 将其编译为 `ADD_FILE`，由 XCodeAgent 投放到 `backend/docs/auth/sql/ddl.sql`；`AuthorizationBootstrapCommand` 显式读取该路径。数据库执行不属于 Strategy Executor，也不记录在 TemplateStateV2。

`002-initialization.sql` 是 V1 静态初始化遗留，不迁入 V2 Migration Asset；初始角色、资源和管理员数据由基于 TechnicalPlan / Application 的动态 Bootstrap 负责。
