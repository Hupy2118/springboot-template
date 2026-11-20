<!-- DEVAGENTSTUDIO:CAPABILITY:login:BEGIN -->
## 认证与登录

`src/providers/LoginProvider.tsx` 的 `LoginProvider` 通过 `src/extensions/providers.ts` Registry 接入 `src/providers/AppProviders.tsx`，内部使用 `src/hooks/useGuard.ts` 的 `useGuard`；`useLogin` 由 `src/context/LoginContext.tsx` 导出。当前用户身份复用 `src/platform/identity/useIdentity.ts`，LoginContext 的 authInfo 保存登录相关配置，不是另一套用户身份上下文。

`src/apis/login.ts` 导出 `loginApi(code)`，通过统一 service 调用 `GET /api/sso/login?code=...`，参数使用 `encodeURIComponent` 编码。`src/pages/Login/index.tsx` 与 `src/pages/Logout/index.tsx` 分别提供 `/login`、`/logout`，经扩展根路由 Registry 注册，不放入普通业务 `PAGE_ROUTES`。

`src/constants/yst.ts` 导出 `YST`，非 local 分支读取 `.env` 中的 `PUBLIC_YHT_CLIENT_ID`、`PUBLIC_YHT_AUTHORIZE_URI`、`PUBLIC_YHT_END_URL`、`PUBLIC_YHT_LOGOUT_SHARE_JS`；回调/登出路径基于当前 origin 的 `/login`、`/logout`。`process.env.WORKS_ENV` 为 local 时使用空认证配置。具体配置值以当前工程为准，不在文档中保存真实凭据。
<!-- DEVAGENTSTUDIO:CAPABILITY:login:END -->
