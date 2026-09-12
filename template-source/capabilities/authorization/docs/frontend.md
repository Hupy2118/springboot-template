# Authorization 前端能力

本文件仅在 Authorization Capability 启用时生成，并随 Login 一同生效。

## 注入内容

- `src/apis/authorization.ts`、授权类型和资源键常量提供授权管理的数据边界。
- `AuthProvider`、`usePermission`、`Permission` 和 `RouteGuard` 提供权限状态与展示/路由守卫。
- 引擎将授权管理页面注册为 Capability 页面路由，并将页面包装器和菜单过滤器接入受管 `src/generated/` 组合入口。

## 维护边界

权限页面、页面守卫和菜单过滤只通过受管 Capability 路由、包装和菜单链组合。不要在 Base 的路由、布局或菜单配置中手工复制权限判断；需要展示控制时复用该能力提供的 Hook 或组件。
