# Login Capability

## 职责与配置

Login 提供当前用户上下文、JWT Cookie 解析、本地 `/api/login/mock` 入口以及前端登录/登出页面与 SSO 守卫。它不提供角色、成员关系、资源目录或任何权限判断。

配置来自 `capability.yaml`：`enabled` 默认为 `true`；`mockLogin` 默认为 `true`，用于允许本地模拟登录入口。Login 无依赖，也没有迁移资产。

## 文件归属

本 Capability 拥有 `frontend/src/login/**` 私有类型与会话常量、登录 API、YST 配置、登录守卫、登录/登出页面、登录 Provider 及后端认证上下文。它不得覆盖 Base 的 `constants/index.ts`、`typings/index.ts`、`App.tsx`、主路由或布局。

## 接入方式

通过 `frontend.app-provider` 贡献登录 Provider，通过 `frontend.routes` 贡献登录/登出根路由，通过 `backend.webmvc-interceptors` 注册当前用户上下文拦截器。贡献顺序固定为 100；生成器负责写入 Base 的受管 `generated/` 组合入口，禁止人工复制共享文件。
