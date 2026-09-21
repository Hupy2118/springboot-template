# 1. 改造范围

代码基础：

```text
template-source/code/frontend/src
```

本次所有新增、修改、删除均限定在该目录内。

不修改：

```text
template-source/base/**
template-source/capabilities/**
template-source/strategy-registry-v2.yaml

template-engine/**
backend/**
```

不实施：

```text
Extension Analyzer
Extension Compiler
Extension Contribution IR
Workbench
Extension 自动注入工具
strategy-registry 自动生成
```

本阶段只解决一件事：

> 将当前前端模板本身改造成一个具备 Extension Runtime 的插件化前端工程，使 Login、Authorization 不再侵入 App、Route、Layout 等宿主代码。

---

# 2. 当前代码存在的核心耦合

基于当前 `template-source/code/frontend/src`，主要存在以下直接依赖。

## 2.1 App.tsx

当前：

```tsx
<GlobalContextProvider>
  <AuthProvider>
    ...
  </AuthProvider>
</GlobalContextProvider>
```

Base App 直接知道：

```text
Login
Authorization
```

需要改成通用 Extension Provider 装配。

---

## 2.2 routes/index.tsx

当前直接：

```tsx
import Login from '@/pages/Login';
import Logout from '@/pages/Logout';
```

并直接依赖：

```text
AuthStateView
usePageMenus
createProtectedRoutes
```

即 Routes 同时知道：

```text
Login
Authorization
```

需要改成：

```text
Extension Root Routes
+
统一 Page Registry
+
Base Access Contract
```

---

# 3. layout/index.tsx

当前直接依赖：

```text
GlobalContext
USER_INFO_KEY
sessionStorage
AuthStateView
usePageMenus
```

说明 Layout 同时知道：

```text
Login 的用户模型
Authorization 的权限模型
```

需要拆成两个通用 Base Contract：

```text
Identity
Access
```

---

# 4. providers/index.tsx

当前：

```tsx
useGuard(...)
```

直接写在全局 Provider 中。

说明通用 Provider 知道 Login。

该逻辑需要整体迁入：

```text
Login Extension
```

---

# 5. constants/routes.tsx

当前：

```tsx
{
  path: 'authorization_management',
  label: '权限管理',
  resourceKey: ...
}
```

Authorization 页面直接写在通用页面配置中。

应迁出为：

```text
Authorization Extension.pageRoutes
```

---

# 6. index.tsx / ErrorBoundary

当前已经分别预留：

```text
Tracking SDK 初始化
Error Reporter
```

不要继续让未来 Tracking 修改这两个宿主文件。

本次提前形成两个稳定 Extension Point：

```text
initializer
errorReporter
```

---

# 7. 目标架构

改造后的目录建议：

```text
template-source/code/frontend/src/
│
├── App.tsx
├── index.tsx
│
├── extension-runtime/
│   ├── contracts.ts
│   ├── registry.ts
│   ├── ordering.ts
│   ├── ExtensionProviders.tsx
│   ├── initializers.ts
│   └── errorReporters.ts
│
├── platform/
│   ├── identity/
│   │   ├── IdentityContext.tsx
│   │   └── useIdentity.ts
│   │
│   └── access/
│       ├── AccessContext.tsx
│       ├── AccessBoundary.tsx
│       ├── AccessStateView.tsx
│       └── useAccess.ts
│
├── extensions/
│   ├── login/
│   │   ├── extension.tsx
│   │   ├── LoginProvider.tsx
│   │   └── ...
│   │
│   └── authorization/
│       ├── extension.tsx
│       ├── AuthorizationProvider.tsx
│       └── ...
│
├── routes/
├── layout/
├── pages/
├── hooks/
└── utils/
```

整体依赖变成：

```text
              App / Route / Layout
                      │
             Extension Runtime
                      │
             ┌────────┴────────┐
             ▼                 ▼
           Login         Authorization
             │                 │
             ▼                 ▼
          Identity           Access
```

Base 不再反向依赖 Login / Authorization。

---

# 8. 第一步：建立 Extension Runtime

新增：

```text
src/extension-runtime/contracts.ts
```

定义第一版 Extension Contract：

```ts
import type {
  ComponentType,
  PropsWithChildren,
} from 'react';

import type {
  RouteObject,
} from 'react-router-dom';

import type {
  PageRouteDefinition,
} from '@/typings/routes';

export type ExtensionProvider =
  ComponentType<PropsWithChildren>;

export type ExtensionInitializer =
  () => void | Promise<void>;

export type ExtensionErrorReporter =
  (
    error: Error,
    info?: unknown,
  ) => void;

export interface FrontendExtension {
  id: string;

  requires?: string[];

  order?: number;

  providers?: ExtensionProvider[];

  rootRoutes?: RouteObject[];

  pageRoutes?: PageRouteDefinition[];

  initializers?: ExtensionInitializer[];

  errorReporters?: ExtensionErrorReporter[];
}

export function defineExtension(
  extension: FrontendExtension,
): FrontendExtension {
  return extension;
}
```

第一版先只开放五类 Extension Point：

```text
providers
rootRoutes
pageRoutes
initializers
errorReporters
```

暂时不要继续增加：

```text
menuTransform
pageRouteGuard
layoutPatch
```

Authorization 的这些需求后续统一由 Access Contract 承担。

---

# 9. 第二步：Extension 自动发现

新增：

```text
src/extension-runtime/registry.ts
```

实现：

```ts
const modules = import.meta.glob<{
  default: FrontendExtension;
}>(
  '@/extensions/*/extension.tsx',
  {
    eager: true,
  },
);
```

所有：

```text
src/extensions/<id>/extension.tsx
```

自动成为 Extension。

生成统一 Registry：

```ts
export const extensions =
  resolveExtensionOrder(
    Object.values(modules).map(
      module => module.default,
    ),
  );
```

继续聚合：

```ts
export const extensionProviders =
  extensions.flatMap(
    extension =>
      extension.providers ?? [],
  );

export const extensionRootRoutes =
  extensions.flatMap(
    extension =>
      extension.rootRoutes ?? [],
  );

export const extensionPageRoutes =
  extensions.flatMap(
    extension =>
      extension.pageRoutes ?? [],
  );

export const extensionInitializers =
  extensions.flatMap(
    extension =>
      extension.initializers ?? [],
  );

export const extensionErrorReporters =
  extensions.flatMap(
    extension =>
      extension.errorReporters ?? [],
  );
```

---

# 10. 第三步：Extension 顺序管理

新增：

```text
src/extension-runtime/ordering.ts
```

实现 Extension 依赖检查与确定性排序。

要求处理：

```text
重复 Extension ID
缺失 requires
循环依赖
```

顺序规则：

```text
requires 拓扑顺序
      ↓
order
      ↓
extension.id
```

例如：

```ts
login

authorization:
  requires: ['login']
```

最终始终：

```text
login
authorization
```

不能依赖文件扫描顺序。

---

# 11. 第四步：App 改造成通用 Provider 装配

新增：

```text
extension-runtime/ExtensionProviders.tsx
```

实现：

```tsx
export function ExtensionProviders({
  children,
}: PropsWithChildren) {
  return extensionProviders.reduceRight(
    (current, Provider) => (
      <Provider>
        {current}
      </Provider>
    ),
    children,
  );
}
```

然后修改：

```text
src/App.tsx
```

删除：

```text
GlobalContextProvider
AuthProvider
```

直接 import。

最终结构：

```tsx
export default function App() {
  return (
    <BrowserRouter>
      <ErrorBoundary>
        <IdentityProvider>
          <StyleProvider layer>
            <ExtensionProviders>
              <ConfigProvider>
                <Routes />
              </ConfigProvider>
            </ExtensionProviders>
          </StyleProvider>
        </IdentityProvider>
      </ErrorBoundary>
    </BrowserRouter>
  );
}
```

其中：

```text
IdentityProvider
```

属于稳定 Base，不属于 Login Extension。

验收：

```text
App.tsx
```

不得出现：

```text
Login
Logout
GlobalContextProvider
AuthProvider
Authorization
```

---

# 12. 第五步：建立 Identity Contract

新增：

```text
src/platform/identity/IdentityContext.tsx
```

Base 只定义统一用户模型：

```ts
export interface Identity {
  userId: string;
  userName: string;
  avatar?: string;
}
```

Context：

```ts
export interface IdentityContextValue {
  identity: Identity | null;

  setIdentity:
    (identity: Identity | null) => void;
}
```

提供：

```text
IdentityProvider
useIdentity()
```

默认：

```text
identity = null
```

Base 不知道：

```text
YHT
SSO
sessionStorage
login URL
logout URL
```

这些全部属于 Login。

---

# 13. 第六步：Login Extension 化

当前 Login 相关代码包括：

```text
pages/Login/**
pages/Logout/**
apis/login.ts
hooks/useGuard.ts
constants/yst.ts
providers/index.tsx 中 Login 相关状态
```

第一轮改造建议真正物理迁移到：

```text
src/extensions/login/
```

建议最终：

```text
extensions/login/
├── extension.tsx
├── LoginProvider.tsx
├── LoginContext.tsx
├── useGuard.ts
├── api.ts
├── constants.ts
├── LoginPage.tsx
└── LogoutPage.tsx
```

## 13.1 LoginContext

Login 自己维护：

```text
loginUrl
logoutUrl
authnSource
```

这些不进入 Base Identity。

## 13.2 LoginProvider

负责：

```text
登录用户恢复
useGuard
认证状态
更新 IdentityContext
```

也就是说当前：

```text
GlobalContextProvider
+
useGuard
```

登录相关职责整体移入：

```text
LoginProvider
```

## 13.3 Login / Logout 页面

改为使用：

```text
useIdentity()
useLogin()
```

不再使用：

```text
GlobalContext
```

---

# 14. Login extension.tsx

新增：

```text
extensions/login/extension.tsx
```

手工维护：

```tsx
export default defineExtension({
  id: 'login',

  order: 100,

  providers: [
    LoginProvider,
  ],

  rootRoutes: [
    {
      path: '/login',
      element: <LoginPage />,
    },
    {
      path: '/logout',
      element: <LogoutPage />,
    },
  ],
});
```

注意：

> 本阶段没有修改 Extension Compiler，所以 `extension.tsx` 就是前端模板源码的一部分，直接维护。

以后工具自动生成它不属于本次范围。

---

# 15. 删除 GlobalContext 对 Login 的承载职责

当前：

```text
src/providers/index.tsx
```

主要承载：

```text
userInfo
authInfo
useGuard
```

这些已经全部迁往：

```text
Identity
+
Login Extension
```

因此改造完成后：

### 如果没有其他使用者

直接删除：

```text
src/providers/index.tsx
```

### 如果还有其他 Base 状态

只保留真正通用状态。

禁止继续保存：

```text
IAuthInfo
Login URL
Logout URL
YHT
```

等 Login 专属字段。

---

# 16. 第七步：建立 Access Contract

新增：

```text
src/platform/access/
```

Base 定义：

```ts
export type AccessState =
  | 'ready'
  | 'loading'
  | 'unauthenticated'
  | 'forbidden'
  | 'not-ready'
  | 'error';
```

统一接口：

```ts
export interface AccessContextValue {
  state: AccessState;

  hasPermission(
    resourceKey: string,
  ): boolean;

  hasAllPermissions(
    resourceKeys: readonly string[],
  ): boolean;

  refresh?: () => Promise<unknown>;
}
```

---

# 17. Access 默认策略

最重要的一条：

> 没有 Authorization Extension 时，普通应用必须默认可访问。

因此：

```ts
const defaultAccess: AccessContextValue = {
  state: 'ready',

  hasPermission:
    () => true,

  hasAllPermissions:
    () => true,
};
```

不要设计成：

```text
无 Authorization
=
无权限
```

因为权限本身是可选 Extension。

---

# 18. AccessBoundary

新增：

```text
platform/access/AccessBoundary.tsx
```

统一实现：

```tsx
export function AccessBoundary({
  resourceKeys,
  children,
}: PropsWithChildren<{
  resourceKeys:
    readonly string[];
}>) {
  const access = useAccess();

  if (
    access.hasAllPermissions(
      resourceKeys,
    )
  ) {
    return <>{children}</>;
  }

  return (
    <AccessStateView
      state={access.state}
    />
  );
}
```

这成为 Base 唯一页面访问控制机制。

---

# 19. AccessStateView

当前：

```text
components/Authorization/RouteGuard.tsx
```

中的：

```text
AuthStateView
```

本质不是 Authorization 页面组件，而是 Base 对 Access 状态的展示。

迁移到：

```text
platform/access/AccessStateView.tsx
```

Authorization Extension 只提供状态。

Base 负责统一显示。

---

# 20. 第八步：Authorization Extension 化

当前 Authorization 相关代码：

```text
apis/authorization.ts
providers/AuthProvider.tsx
components/Authorization/**
hooks/usePermission.ts
constants/resources.ts
typings/authorization.ts
pages/AuthorizationManagement/**
utils/protectedRoutes.tsx 中权限逻辑
hooks/usePageMenus.ts 中权限逻辑
```

Extension 自有实现迁往：

```text
extensions/authorization/
```

建议：

```text
extensions/authorization/
├── extension.tsx
├── AuthorizationProvider.tsx
├── Permission.tsx
├── usePermission.ts
├── api.ts
├── resources.ts
├── types.ts
└── pages/
    └── AuthorizationManagement/
```

---

# 21. AuthorizationProvider

当前：

```text
AuthProvider
```

改名：

```text
AuthorizationProvider
```

职责变成：

```text
请求 resourceKeys
        ↓
转换 Authorization 状态
        ↓
通过 AccessContext.Provider
覆盖 Base 默认 AccessPolicy
```

即：

```tsx
return (
  <AccessContext.Provider
    value={value}
  >
    {children}
  </AccessContext.Provider>
);
```

Base 不知道：

```text
getMyResources
AuthorizationApiError
resourceKeys
```

---

# 22. Permission 组件

`Permission` 仍属于 Authorization Extension。

但内部不要直接依赖：

```text
AuthProvider
```

而统一：

```ts
const {
  hasPermission,
} = useAccess();
```

这样 Permission 本身建立在 Base Access Contract 上。

---

# 23. Authorization extension.tsx

新增：

```text
extensions/authorization/extension.tsx
```

内容：

```tsx
export default defineExtension({
  id: 'authorization',

  requires: [
    'login',
  ],

  order: 200,

  providers: [
    AuthorizationProvider,
  ],

  pageRoutes: [
    {
      path:
        'authorization_management',

      label:
        '权限管理',

      icon:
        'SafetyCertificateOutlined',

      resourceKey:
        'system_authorization_management',
    },
  ],
});
```

这里继续使用当前 `code/frontend/src/typings/routes.ts` 已有的：

```text
path
label
icon
resourceKey
visible
children
```

模型。

**不要为了插件化改成另一套路由模型。**

---

# 24. 第九步：constants/routes.tsx 收回 Base 页面

当前：

```text
constants/routes.tsx
```

删除：

```text
authorization_management
```

只保留真正的应用页面：

```tsx
export const PAGE_ROUTES:
  PageRouteDefinition[] = [
    {
      path: 'home',
      label: '首页',
    },

    // XCODEAGENT_BUSINESS_ROUTES_START
    // XCODEAGENT_BUSINESS_ROUTES_END
  ];
```

Authorization 页面来自：

```text
extensionPageRoutes
```

---

# 25. 第十步：新增统一 Page Registry

新增：

```text
src/routes/pageRegistry.ts
```

内容：

```ts
import {
  PAGE_ROUTES,
} from '@/constants/routes';

import {
  extensionPageRoutes,
} from '@/extension-runtime/registry';

import {
  resolvePageRouteTree,
} from '@/utils/pageRouteTree';

export const APP_PAGE_ROUTES = [
  ...PAGE_ROUTES,
  ...extensionPageRoutes,
];

export const APP_PAGE_ROUTE_TREE =
  resolvePageRouteTree(
    APP_PAGE_ROUTES,
    PAGE_ROUTE,
  );
```

从此：

```text
PAGE_ROUTES
```

只是 Base / 业务页面。

```text
APP_PAGE_ROUTES
```

才是运行时完整页面集合。

---

# 26. 第十一步：路由权限 Base 化

当前：

```text
utils/protectedRoutes.tsx
```

实际上是 Authorization 专用命名。

建议改成：

```text
routes/routeBuilder.tsx
```

或：

```text
utils/accessRoutes.tsx
```

推荐：

```text
routes/routeBuilder.tsx
```

因为职责已经是通用路由构建。

核心逻辑：

```text
ResolvedPageRoute
        ↓
createPageRoutes
        ↓
resourceKey?
     /       \
   no         yes
   ↓           ↓
Page     AccessBoundary(Page)
```

不再：

```text
RouteBuilder
→ Authorization RouteGuard
```

---

# 27. 页面 resourceKey 继续作为 Base Contract

当前：

```ts
PageRouteDefinition {
  resourceKey?: string;
}
```

保留。

这是很好的 Base Contract。

含义：

```text
没有 resourceKey
→ 不参与 Access 控制

有 resourceKey
→ 使用 AccessPolicy 判断
```

Base 不需要知道 AccessPolicy 来自：

```text
Authorization
其他 IAM
Mock Access
```

哪一个 Extension。

---

# 28. 第十二步：usePageMenus Base 化

当前：

```text
hooks/usePageMenus.ts
```

直接：

```text
useAuth()
usePermission()
```

修改为：

```text
useAccess()
APP_PAGE_ROUTE_TREE
```

例如：

```ts
export function usePageMenus() {
  const {
    state,
    hasPermission,
  } = useAccess();

  const navigation = useMemo(
    () =>
      createAccessibleNavigation(
        hasPermission,
        APP_PAGE_ROUTE_TREE,
      ),
    [
      hasPermission,
    ],
  );

  return {
    state,
    ...navigation,
  };
}
```

Authorization 实现彻底退出该 Hook。

---

# 29. route.tsx 去 Authorization 化

当前：

```text
createAuthorizedNavigation
```

建议改名：

```text
createAccessibleNavigation
```

因为这里实际处理的是：

```text
AccessPolicy
```

而不是 Authorization 实现。

函数输入继续是：

```ts
(resourceKey) => boolean
```

即可。

---

# 30. 第十三步：Layout 去 Login / Authorization 化

修改：

```text
layout/index.tsx
```

删除：

```text
GlobalContext
USER_INFO_KEY
sessionStorage
AuthStateView from Authorization
```

改成：

```ts
const {
  identity,
} = useIdentity();

const {
  state,
  menuRoutes,
} = usePageMenus();
```

Avatar：

```tsx
avatarProps={
  identity
    ? {
        src:
          identity.avatar ?? '',

        title:
          identity.userName,

        ...
      }
    : undefined
}
```

Menu 状态：

```tsx
<AccessStateView
  state={state}
/>
```

Layout 最终只依赖：

```text
Identity Contract
Access Contract
```

---

# 31. 第十四步：Root Route 插件化

当前：

```text
routes/index.tsx
```

直接注册：

```text
/login
/logout
```

删除。

改为：

```ts
import {
  extensionRootRoutes,
} from '@/extension-runtime/registry';
```

路由结构：

```tsx
const routeList:
  RouteObject[] = [
    {
      path: '/',

      children: [
        ...extensionRootRoutes,

        {
          path:
            PAGE_ROUTE,

          element:
            <Layout />,

          children: [
            ...
          ],
        },

        ...
      ],
    },
  ];
```

Routes 不知道 Login Extension。

---

# 32. 首页跳转逻辑保留在 Base

当前：

```text
PageEntryRedirect
```

是通用应用行为。

保留。

但数据来源改成：

```text
APP_PAGE_ROUTE_TREE
+
usePageMenus()
```

无 Authorization 时：

```text
AccessPolicy = allow all
```

所以会自然跳转到第一个页面。

---

# 33. 第十五步：Initializer Extension Point

修改：

```text
index.tsx
```

删除当前 Tracking TODO 注释。

新增：

```text
extension-runtime/initializers.ts
```

实现：

```ts
export async function
runExtensionInitializers() {
  for (
    const initializer
    of extensionInitializers
  ) {
    await initializer();
  }
}
```

启动：

```ts
async function bootstrap() {
  await runExtensionInitializers();

  const root =
    ReactDOM.createRoot(...);

  root.render(
    <App />,
  );
}

void bootstrap();
```

以后 Tracking 不需要修改 `index.tsx`。

本阶段不实现 Tracking Extension。

---

# 34. 第十六步：Error Reporter Extension Point

修改：

```text
components/ErrorBoundary/index.tsx
```

删除：

```text
TODO Tracking
```

新增：

```text
extension-runtime/errorReporters.ts
```

实现：

```ts
export function reportExtensionError(
  error: Error,
  info?: unknown,
) {
  for (
    const reporter
    of extensionErrorReporters
  ) {
    try {
      reporter(
        error,
        info,
      );
    } catch (
      reporterError
    ) {
      console.error(
        reporterError,
      );
    }
  }
}
```

ErrorBoundary：

```ts
componentDidCatch(
  error,
  info,
) {
  this.setState({
    hasError: true,
  });

  console.error(
    '[ErrorBoundary]',
    error,
  );

  reportExtensionError(
    error,
    info,
  );
}
```

---

# 35. 本次不做 App Guard Extension Point

和前一版相比，这里建议进一步收敛。

当前 Login 的：

```text
useGuard
```

本质可以由：

```text
LoginProvider
```

负责。

因此本次先不增加：

```text
appGuards
```

Extension Point。

避免为了“插件化”预先设计没有必要的抽象。

第一版只保留实际已经出现的：

```text
providers
rootRoutes
pageRoutes
initializers
errorReporters
```

以后出现第二个确切的 App Guard 使用场景，再新增正式 Extension Point。

---

# 36. 第一阶段建议不要增加 Layout Extension Point

当前：

```text
Layout title
logo
actionsRender
theme
```

都不属于 Login / Authorization。

本次不做：

```text
layoutPlugin
layoutPatch
headerExtension
```

避免过度设计。

后续出现真实需求后再决定：

```text
配置项
还是
Extension Point
```

---

# 37. 最终依赖关系

完成后必须形成：

```text
App
 │
 ├── IdentityProvider
 ├── ExtensionProviders
 └── Routes
        │
        ▼
     Layout
        │
     ┌──┴───┐
     ▼      ▼
 Identity  Access

Extension Runtime
     │
 ┌───┴───────────┐
 ▼               ▼
Login       Authorization
 │               │
 ▼               ▼
Identity       Access
```

禁止：

```text
App → Login
App → Authorization

Routes → Login
Routes → Authorization

Layout → Login
Layout → Authorization
```

---

# 38. 文件级实施清单

## 新增

```text
src/extension-runtime/
  contracts.ts
  registry.ts
  ordering.ts
  ExtensionProviders.tsx
  initializers.ts
  errorReporters.ts

src/platform/identity/
  IdentityContext.tsx
  useIdentity.ts

src/platform/access/
  AccessContext.tsx
  useAccess.ts
  AccessBoundary.tsx
  AccessStateView.tsx

src/routes/
  pageRegistry.ts
  routeBuilder.tsx

src/extensions/login/
  extension.tsx
  LoginContext.tsx
  LoginProvider.tsx
  useGuard.ts
  api.ts
  constants.ts
  LoginPage.tsx
  LogoutPage.tsx

src/extensions/authorization/
  extension.tsx
  AuthorizationProvider.tsx
  Permission.tsx
  usePermission.ts
  api.ts
  resources.ts
  types.ts
  pages/AuthorizationManagement/**
```

---

# 39. 修改

重点修改：

```text
src/App.tsx
src/index.tsx

src/routes/index.tsx

src/layout/index.tsx

src/constants/routes.tsx

src/hooks/usePageMenus.ts

src/utils/route.tsx

src/components/ErrorBoundary/index.tsx
```

---

# 40. 迁移后删除

确认无引用后删除旧实现：

```text
src/apis/login.ts
src/apis/authorization.ts

src/hooks/useGuard.ts
src/hooks/usePermission.ts

src/providers/AuthProvider.tsx

src/pages/Login/**
src/pages/Logout/**
src/pages/AuthorizationManagement/**

src/components/Authorization/**

src/constants/resources.ts

src/typings/authorization.ts
```

对于：

```text
src/providers/index.tsx
```

需要先检查是否仍存在非 Login 用途。

如果只承载 Login 状态，则删除。

---

# 41. 暂不删除的兼容文件

如果本次 Codex 回检发现 `src` 中还有其他代码依赖旧路径，不要为了完成目录迁移一次性破坏所有调用。

可以短期保留：

```ts
export {
  Permission,
} from '@/extensions/authorization/Permission';
```

这种 Compatibility Re-export。

但必须：

```text
标记为 deprecated
不得包含业务逻辑
```

下一阶段再清理。

---

# 42. 实施顺序

要求 Codex 严格按下面顺序改，不建议同时进行。

## Step 1

建立：

```text
extension-runtime
```

先保证：

```text
import.meta.glob
Extension 排序
Extension Registry
```

可正常工作。

---

## Step 2

建立：

```text
Identity Contract
```

迁移 Login。

验证：

```text
App
Routes
Layout
```

均不再直接依赖 Login。

---

## Step 3

建立：

```text
Access Contract
```

迁移 Authorization。

验证：

```text
Routes
Layout
usePageMenus
routeBuilder
```

均不再依赖 Authorization。

---

## Step 4

迁移：

```text
pageRoutes
rootRoutes
```

到 Extension Descriptor。

清理：

```text
constants/routes.tsx
routes/index.tsx
```

中的能力代码。

---

## Step 5

加入：

```text
initializer
errorReporter
```

为后续 Tracking 建立正式 Extension Point。

---

## Step 6

清理旧：

```text
@xcodeagent-extension
```

注释和无效代码。

---

# 43. 验收一：代码依赖

执行代码搜索。

以下文件不得出现：

```text
login
authorization
AuthProvider
GlobalContext
```

能力实现 import：

```text
src/App.tsx
src/routes/index.tsx
src/layout/index.tsx
```

允许出现：

```text
Identity
Access
Extension
```

等通用抽象。

---

# 44. 验收二：Extension Runtime

当前工程必须自动发现：

```text
login
authorization
```

两个 Extension。

验证：

```text
Extension ID 唯一
authorization requires login
排序结果稳定
```

人为删除 Login Extension 后，应明确报：

```text
MISSING_EXTENSION_DEPENDENCY
```

而不是静默运行。

---

# 45. 验收三：Login

必须验证：

```text
/login
/logout
```

仍然注册。

登录后：

```text
Identity
```

能够更新。

Layout Avatar 能读取：

```text
useIdentity()
```

Login 逻辑不得要求修改：

```text
App.tsx
Routes
Layout
```

---

# 46. 验收四：Authorization

验证：

```text
AuthorizationProvider
```

能够覆盖 Base 默认 AccessPolicy。

权限页面：

```text
authorization_management
```

通过：

```text
extension.pageRoutes
```

进入页面树。

受控页面：

```text
resourceKey
```

无权限时：

```text
菜单隐藏
直接访问阻断
```

无 `resourceKey` 页面：

```text
默认允许
```

---

# 47. 验收五：无 Authorization 场景

临时从 Registry 中移除 Authorization Extension 或删除对应：

```text
extensions/authorization/extension.tsx
```

应用必须仍然：

```text
build PASS
普通页面可访问
菜单正常
```

用于验证：

```text
Access 默认允许
```

是否真正生效。

---

# 48. 验收六：无 Login / Authorization 场景

临时仅保留 Base。

要求：

```text
pnpm build
```

成功。

应用基础页面仍可打开。

这一条用于确认：

> Base 本身不依赖任何具体 Extension。

---

# 49. 验收七：构建

至少执行：

```bash
pnpm build
```

必须通过。

同时检查：

```text
TypeScript 编译错误
未使用 import
路径大小写
循环引用
import.meta.glob 是否找到 extension.tsx
```

---

# 50. Codex 明确禁止事项

本次改造禁止修改：

```text
template-source/base/**
template-source/strategy-registry-v2.yaml
template-source/capabilities/**
template-engine/**
backend/**
```

禁止为了完成前端插件化新增：

```text
Anchor
TEXT_ANCHOR_INSERT
ENSURE_IMPORT
Compiler 特殊分支
```

禁止：

```ts
if (
  extension.id === 'login'
)
```

之类 Base 特判。

禁止创建：

```text
LoginExtensionRuntime
AuthorizationExtensionRuntime
```

这种能力专属 Runtime。

统一通过：

```text
FrontendExtension
Extension Registry
Identity
Access
```

处理。

---

# 51. 本阶段完成后的状态

本次改造完成以后：

```text
template-source/code/frontend/src
```

自身就应该是一个完整的插件化前端模板：

```text
Base Frontend
    │
    ├── Extension Runtime
    ├── Identity Contract
    ├── Access Contract
    ├── Route Infrastructure
    └── Layout

Extensions
    │
    ├── Login
    └── Authorization
```

运行方式：

```text
extension.tsx
      ↓
import.meta.glob
      ↓
Extension Registry
      ↓
Provider / Route / Page
      ↓
Base Application
```

这一阶段**不解决 Extension 是怎么被自动注入模板的**。

也就是说：

```text
extensions/login/extension.tsx
extensions/authorization/extension.tsx
```

现在仍然是 `template-source/code/frontend/src` 中直接维护的源码。

本次只证明：

> **前端模板自身已经具备标准 Extension 架构，并且 Login / Authorization 可以作为插件独立存在。**

等这一层稳定后，再单独讨论后续 Authoring / Compiler 如何从 Extension Source 自动生成这些内容，避免把“前端架构改造”和“模板注入工具改造”混在同一个阶段。

---

# 52. Runtime 硬约束补充

以下约束是 Login / Authorization 迁移之前必须先建立并通过测试的
Extension Runtime 契约。不得为了迁移某个具体 Capability 再回头修改这些
基础语义。

## 52.1 根路由必须是通用入口

Base 根路径不得依赖 Login Extension。

Base 根路径统一进入：

```text
/page
或
PageEntryRedirect
```

Login Extension 只贡献：

```text
/login
/logout
```

等扩展根路由。

因此：

```text
Base-only
  ↓
直接进入第一个可访问页面
  ↓
没有页面时展示“暂无可访问页面”
```

Base 路由不得固定重定向至：

```text
/login
```

## 52.2 Extension 组合测试必须拆分

下列场景分别验证不同契约，禁止通过“删除 Login”同时验证两个目标：

```text
[]
  → 纯 Base 可运行；空 Extension 集合是合法的一等场景

[login]
  → Login 可独立运行

[login, authorization]
  → 完整组合可运行

[authorization]
  → 必须报 MISSING_EXTENSION_DEPENDENCY
```

## 52.3 Provider 顺序是正式契约

当：

```text
authorization requires login
```

拓扑顺序必须为：

```text
login
→
authorization
```

Provider 嵌套必须为：

```text
LoginProvider
  └── AuthorizationProvider
```

即：

> 被依赖 Extension 的 Provider 在外层，依赖方 Extension 的 Provider 在内层。

同一个 Extension 内多个 Provider 也采用声明数组从外到内的顺序：

```ts
providers: [
  OuterProvider,
  InnerProvider,
]
```

必须渲染为：

```tsx
<OuterProvider>
  <InnerProvider>
    {children}
  </InnerProvider>
</OuterProvider>
```

上述两类顺序均必须由单测锁定。

## 52.4 Access 状态与权限判断必须分离

页面访问按以下优先级执行：

```text
无 resourceKey
  → 直接放行

有 resourceKey 且 state !== ready
  → 直接展示对应状态，不调用 hasPermission / hasAllPermissions

有 resourceKey 且 state === ready
  → 调用 hasAllPermissions
      ├── true  → 放行
      └── false → Forbidden
```

状态展示规则：

```text
loading          → Loading
unauthenticated  → 未登录状态
not-ready        → 权限运行时未就绪
error            → Error
```

`forbidden` 是：

```text
state === ready
且无页面所需权限
```

时由 `AccessBoundary` 派生的页面展示结果，不应与 Provider 的非就绪运行状态
混用。权限接口返回 HTTP 403 时必须明确映射为 `unauthenticated`、`error` 或另行
定义的全局拒绝状态；不得将它含混地等同于某个页面的资源权限不足。

## 52.5 自动发现必须可诊断且 Fail-fast

Runtime 固定执行以下流程：

```text
Extension Discovery
        ↓
Validation
        ↓
Dependency Resolution
        ↓
Deterministic Ordering
        ↓
Contribution Aggregation
        ↓
Provider / Route / Access Runtime
```

必须定义并使用固定 Runtime 错误类型：

```text
DUPLICATE_EXTENSION_ID
MISSING_EXTENSION_DEPENDENCY
CIRCULAR_EXTENSION_DEPENDENCY
INVALID_EXTENSION_DEFINITION
```

错误必须携带足以定位问题的诊断信息，包括：

```text
extensionId
缺失依赖 ID
循环依赖路径
无效定义的具体字段或原因
```

`registry.ts` 只负责通过 `import.meta.glob` 自动发现模块。接收 Extension 数组、
校验定义、校验依赖、排序和聚合 Contribution 的逻辑必须抽取为纯函数，并由
Registry 单测直接覆盖；不得将错误发现完全依赖于 `pnpm build`。

## 52.6 requires 的边界

`requires` 只描述 Extension 生命周期和装配顺序依赖，不得被用作业务运行时的
跨 Extension 耦合机制。

运行时代码优先通过稳定 Base Contract 解耦：

```text
Identity
Access
以及后续经过验证后新增的通用 Contract
```

不得通过 Capability ID 判断、直接读取另一个 Extension 的内部状态，或以
`requires` 替代运行时 Contract。


# 53. 问题修复

可以，建议把这次修复定义为：

> **保留上一次已经完成的 Login / Authorization 解耦成果，但将“运行时 Extension”修正为“模板编译期 Extension”，最终 `template-source/code/frontend/src` 恢复为普通、整洁的 React 工程，不暴露 Extension 概念。**

本次先只修复前端模板源码，不涉及后续 Extension Compiler、Authoring Tool 等工具实现。

### 修复实施步骤

1. **取消最终工程中的 Runtime Extension 机制**
   删除或停止使用当前新增的：

   ```text
   src/extensions/**
   src/extension-runtime/**
   extension.tsx
   import.meta.glob(...)
   FrontendExtension / defineExtension
   Extension Registry / Runtime Ordering
   ```

   其中 Login / Authorization 业务代码不要删除，只做后续迁移。`ordering.ts` 中依赖排序等思想保留为后续模板工具能力，不再属于生成应用。

2. **将 Login 代码恢复到正常应用目录**
   把当前：

   ```text
   extensions/login/api.ts
   extensions/login/LoginPage.tsx
   extensions/login/LogoutPage.tsx
   extensions/login/LoginProvider.tsx
   extensions/login/useGuard.ts
   ...
   ```

   调整为普通工程结构，例如：

   ```text
   src/apis/login.ts
   src/pages/Login/index.tsx
   src/pages/Logout/index.tsx
   src/providers/LoginProvider.tsx
   src/hooks/useGuard.ts
   ```

   `LoginContext`、配置和类型同样放回 `context/constants/typings` 等正常目录。保留上次改造中 Login 与 Base 解耦后的职责划分，不再恢复到 `GlobalContext` 强耦合模式。

3. **将 Authorization 代码恢复到正常应用目录**
   把当前：

   ```text
   extensions/authorization/**
   ```

   迁回：

   ```text
   src/apis/authorization.ts
   src/pages/AuthorizationManagement/**
   src/providers/AuthorizationProvider.tsx
   src/components/Authorization/Permission.tsx
   src/hooks/usePermission.ts
   src/constants/resources.ts
   src/typings/authorization.ts
   src/typings/generated/**
   ```

   保留已经形成的 `Access Contract`，AuthorizationProvider 继续通过 Access Contract 提供权限能力，不让 Layout/Routes 重新直接依赖 Authorization 实现。

4. **保留 Identity / Access 作为正式 Base Contract**
   上次改造中新增的：

   ```text
   src/platform/identity/**
   src/platform/access/**
   ```

   建议保留。它们不是 Extension 内部概念，而是最终应用合理的平台抽象。依赖继续保持：

   ```text
   LoginProvider
       ↓
   Identity Contract

   AuthorizationProvider
       ↓
   Access Contract
   ```

   `Layout` 只消费 `useIdentity()`、`useAccess()`，不重新读取 Login/Authorization 私有状态。

5. **把 Provider Runtime 注册改成普通静态聚合文件**
   新增或调整：

   ```text
   src/providers/AppProviders.tsx
   ```

   当前 Login + Authorization 完整模板中直接静态组合：

   ```tsx
   <LoginProvider>
     <AuthorizationProvider>
       {children}
     </AuthorizationProvider>
   </LoginProvider>
   ```

   `App.tsx` 只依赖：

   ```tsx
   <AppProviders>
     <Routes />
   </AppProviders>
   ```

   同时锁定 Provider 顺序：被依赖者在外层，例如 `authorization requires login`，所以 LoginProvider 在 AuthorizationProvider 外层。未来这里由模板 Compiler 根据 Contribution 生成，本次先形成正确的静态结果。

6. **把 Route Runtime 注册改成普通静态聚合**
   不再从 `extension.tsx` 动态收集 `/login`、`/logout`。新增类似：

   ```text
   src/routes/rootRoutes.tsx
   ```

   静态维护当前模板已有：

   ```text
   /login
   /logout
   ```

   `routes/index.tsx` 只组合 `rootRoutes + page routes`。同时修正根路由：

   ```text
   / → /page → PageEntryRedirect
   ```

   不再固定 `/ → /login`，确保未来 Base-only 场景成立。

7. **将 Authorization 页面并入统一 Page Registry**
   `AuthorizationManagement` 重新作为普通页面放到：

   ```text
   src/pages/AuthorizationManagement/
   ```

   通过统一：

   ```text
   src/routes/pageRegistry.ts
   ```

   与业务页面一起进入路由树。`PageRouteDefinition.resourceKey` 继续作为通用 Access Contract。路由构建规则保持：

   ```text
   state != ready
       → AccessStateView

   state == ready
       → 判断 resourceKey
       → allowed / forbidden
   ```

   不恢复 Authorization 专属 `RouteGuard Patch`。

8. **Initializer / ErrorReporter 暂时收敛为普通应用接口**
   如果上次已经增加 Runtime Extension Point，不再以 `extension.initializers/errorReporters` 暴露。可以保留普通文件，例如：

   ```text
   src/bootstrap/appInitializers.ts
   src/observability/errorReporter.ts
   ```

   当前没有 Tracking 时提供空实现。未来 Tracking Extension 发布时，由 Compiler 静态生成这里的调用关系。

9. **清理 Extension 痕迹并完成回归**
   清理源码中的：

   ```text
   @xcodeagent-extension
   FrontendExtension
   defineExtension
   extension.tsx
   ExtensionRegistry
   import.meta.glob extensions
   ```

   最终 `src` 应呈现普通工程结构。执行 `pnpm build`，并回归 Login、Logout、AuthorizationManagement、菜单权限、页面直接访问权限、Permission 组件以及根页面跳转。

### 修复后的目标状态

模板维护层未来仍然可以是：

```text
Base
+
Login Extension
+
Authorization Extension
+
Extension Contribution
```

但当前完整前端模板应该表现为：

```text
src/
├── App.tsx
├── apis/
├── pages/
├── providers/
│   └── AppProviders.tsx
├── hooks/
├── components/
├── routes/
│   ├── index.tsx
│   ├── rootRoutes.tsx
│   └── pageRegistry.ts
├── platform/
│   ├── identity/
│   └── access/
└── ...
```

这次修复的核心不是推翻上一次改造，而是**保留“Login / Authorization 不再 Patch Base”的解耦结果，把 `Extension Runtime` 从用户工程中拿掉，并改成静态 Assembly。后续再由模板工具负责生成这些 Assembly 文件。**
