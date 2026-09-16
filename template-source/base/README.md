# Base 模板

## 职责与边界

Base 是可独立构建、运行的 Spring Boot + React 骨架，拥有公共响应与异常、构建基线、基础布局、页面发现机制和默认欢迎页。空 Capability 集访问 `/page/welcome`，不需要数据库初始化、登录或权限数据。

Base 不拥有登录、认证、授权、角色、成员、资源表、权限 API、登录/登出路由或权限菜单。它不得直接导入任何 Capability 的模块。

## 前端受管组合入口

Base 的 `App`、主路由和布局只导入下列 Capability Extension Surface：

- `frontend/src/capability-extensions/providers.tsx`：声明 Provider 注册列表。
- `frontend/src/capability-extensions/routes.tsx`：声明根路由、页面路由与页面包装器。
- `frontend/src/capability-extensions/menuTransforms.ts`：提供菜单投影后的处理。
- `frontend/src/capability-extensions/routeGuards.tsx`：提供 App / Page Route Guard 注册面。

这些文件是 Authoring 的唯一前端修改入口：只允许新增 import，以及在已登记 Anchor 前新增内容。Provider Tree、路由入口计算和页面包装执行位于 Base 内部文件，不能由 Capability 修改。Base 的其他共享文件也不得被 Capability 覆盖。

## 扩展点与迁移

Provider、路由、菜单与 Web MVC 的固定扩展面由 `strategy-registry-v2.yaml` 的 target registry 管理。它们是 platform-managed surface：Capability 只能通过已登记的 Atomic Strategy 追加受管块，不能整体重写文件骨架。

Base 没有迁移资产和配置项。数据库、npm 或 Maven 变更必须由 Capability 通过结构化 `dependencies` 声明，不能复制或拼接 Base 的 `package.json`、`pom.xml`。
