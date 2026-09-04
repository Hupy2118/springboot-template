# Authorization Capability

## 职责与配置

Authorization 提供角色、成员、资源、接口权限校验、权限 API、授权管理页、权限 Provider、路由守卫和菜单过滤。它 `required` 依赖 Login，因为所有权限判断均从 Login 提供的当前用户上下文读取成员标识。

配置项只有 `enabled`，默认 `true`。它通过结构化 npm 依赖声明 `ahooks`，不提交或拼接 Base 的 `package.json`。

## 文件归属与前端接入

本 Capability 拥有授权 API/类型、资源键、`AuthProvider`、权限 Hook/组件、受保护路由、授权管理页以及后端 auth 实现和 Mapper。它不拥有 Base 的 `App.tsx`、主路由、布局、通用常量或通用路由类型。

它按顺序 200 贡献 `frontend.providers`、`frontend.page-routes`、`frontend.page-wrappers`、`frontend.menu-hooks` 与 `backend.spring-interceptors`。组合结果由受管 `generated/` 文件生成；不得通过手工修改 Base 共享文件接入授权能力。

## 迁移

`schema`（顺序 100）复制 `001-schema.sql`，`initialization`（顺序 200）复制 `002-initialization.sql`。迁移元数据位于 `migrations/migration.yaml`；它们是受管 SQL 资产，关闭 Capability 不会自动回滚已经执行的数据库变更，回滚方式为人工处理。
