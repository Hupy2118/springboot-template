# Login 前端能力

本文件仅在 Login Capability 启用时生成。它与登录 API、Provider、路由和页面一起加入前端工程。

## 注入内容

- `src/apis/login.ts` 调用模拟登录接口。
- `src/providers/index.tsx` 提供全局登录上下文；引擎将它接入 `src/capability-extensions/providers.tsx` 的受管注册列表。
- `src/pages/Login/` 与 `src/pages/Logout/` 分别由引擎注册到 `/login` 和 `/logout` 根路由。
- `RequireLogin` 作为 App Route Guard 保护主业务 `PAGE_ROUTE` 分支；登录、登出等根路由始终位于 Guard 外。

## 维护边界

登录、登出路由、Provider 和页面包装器必须通过 `src/generated/` 的受管组合入口接入。不要在 Base 路由、布局或菜单中手工复制第二份登录注册逻辑。
