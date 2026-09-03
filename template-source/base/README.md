# Base 模板

## 职责与边界

Base 是可独立构建、运行的 Spring Boot + React 骨架，拥有公共响应与异常、构建基线、基础布局、页面发现机制和默认欢迎页。空 Capability 集访问 `/page/welcome`，不需要数据库初始化、登录或权限数据。

Base 不拥有登录、认证、授权、角色、成员、资源表、权限 API、登录/登出路由或权限菜单。它不得直接导入任何 Capability 的模块。

## 前端受管组合入口

Base 的 `App`、主路由和布局只导入下列受管文件：

- `frontend/src/generated/capabilityProviders.tsx`：按稳定顺序包装 Provider；空集为恒等包装。
- `frontend/src/generated/capabilityRoutes.tsx`：提供根路由、页面路由与页面包装器；空集为空贡献。
- `frontend/src/generated/capabilityMenus.ts`：提供菜单后处理；空集原样返回 Base 菜单。

这些文件由 Core 完整生成，不能由 Capability、应用开发者或人工直接修改。Base 的其他共享文件也不得被 Capability 覆盖。

## 扩展点与迁移

唯一允许的共享扩展点在 `extension-registry.yaml` 中登记。Capability 只能以 manifest Contribution 使用已登记的 Provider、路由、页面路由、菜单或 Web MVC 拦截器扩展点；未登记的目标一律失败。

Base 没有迁移资产和配置项。数据库、npm 或 Maven 变更必须由 Capability 通过结构化 `dependencies` 声明，不能复制或拼接 Base 的 `package.json`、`pom.xml`。
