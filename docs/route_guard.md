## Route Guard 的确定性组合规则

Login 与 Authorization 统一使用 `Guard Route + Outlet` 模型。App Guard 与 Page Guard 使用相同的底层组合算法，区别仅在于插入 Route Tree 的位置。

### 1. Guard 注册顺序

Guard 在注册数组中的顺序定义为：

```text
前 → 后
=
外层 → 内层
```

例如：

```ts
pageRouteGuards = [
  createGuardA,
  createGuardB,
];
```

最终 Route Tree 必须确定性生成：

```text
GuardA
  ↓
GuardB
  ↓
Page
```

因此统一使用 `reduceRight` 从最终业务 Route 向外构造。

---

### 2. Page Route Guard

Page Guard Factory 不直接返回完整业务 Route，而只描述当前 Guard。

建议约束：

```ts
export interface RouteGuardDefinition {
  element: ReactNode;
}

export type PageRouteGuardFactory = (
  page: PageRouteDefinition,
) => RouteGuardDefinition | undefined;
```

例如 Authorization：

```tsx
export function createAuthorizationRouteGuard(
  page: PageRouteDefinition,
): RouteGuardDefinition | undefined {
  if (!page.resourceKey) {
    return undefined;
  }

  return {
    element: (
      <AuthorizationGuard
        resourceKey={page.resourceKey}
      />
    ),
  };
}
```

其中：

```tsx
function AuthorizationGuard({
  resourceKey,
}: {
  resourceKey: string;
}) {
  const { hasPermission } = useAuthorization();

  if (!hasPermission(resourceKey)) {
    return <Forbidden />;
  }

  return <Outlet />;
}
```

Route Builder 先生成最终业务 Page Route：

```ts
const pageRoute: RouteObject = {
  path: resolvePagePath(page),
  element: <PageComponent />,
};
```

再按照 Guard 注册顺序进行确定性包装：

```ts
function applyPageRouteGuards(
  page: PageRouteDefinition,
  pageRoute: RouteObject,
): RouteObject {
  return pageRouteGuards.reduceRight<RouteObject>(
    (childRoute, createGuard) => {
      const guard = createGuard(page);

      if (!guard) {
        return childRoute;
      }

      return {
        element: guard.element,
        children: [childRoute],
      };
    },
    pageRoute,
  );
}
```

最终算法：

```text
PageRouteDefinition
        ↓
构造最终 Page Route
        ↓
pageRouteGuards.reduceRight
        ↓
Guard Route
        ↓
Guard Route
        ↓
最终 Page Route
```

例如：

```ts
pageRouteGuards = [
  createGuardA,
  createGuardB,
];
```

生成：

```text
{
  element: <GuardA />,
  children: [
    {
      element: <GuardB />,
      children: [
        {
          path: 'page_c',
          element: <PageC />,
        },
      ],
    },
  ],
}
```

即：

```text
GuardA
  ↓
GuardB
  ↓
PageC
```

如果某个 Factory 返回 `undefined`，表示该 Guard 对当前页面不适用，直接跳过。

例如页面没有：

```text
resourceKey
```

则 Authorization Factory 返回 `undefined`，最终不会产生空的 Authorization Guard Route。

---

### 3. App Route Guard

App Guard 使用相同的组合原则，但它不接收具体 Page，而是保护整个主业务 Route 分支。

定义：

```ts
export type AppRouteGuardFactory = () =>
  RouteGuardDefinition | undefined;
```

Login：

```tsx
export function createLoginRouteGuard(): RouteGuardDefinition {
  return {
    element: <RequireLogin />,
  };
}
```

```tsx
function RequireLogin() {
  const { userInfo } = useContext(GlobalContext);
  const location = useLocation();

  if (!userInfo) {
    return (
      <Navigate
        to="/login"
        replace
        state={{ from: location.pathname }}
      />
    );
  }

  return <Outlet />;
}
```

App Guard 同样使用 `reduceRight`：

```ts
function applyAppRouteGuards(
  appRoute: RouteObject,
): RouteObject {
  return appRouteGuards.reduceRight<RouteObject>(
    (childRoute, createGuard) => {
      const guard = createGuard();

      if (!guard) {
        return childRoute;
      }

      return {
        element: guard.element,
        children: [childRoute],
      };
    },
    appRoute,
  );
}
```

---

### 4. App Guard 的作用范围

App Guard **只能包装主业务 `PAGE_ROUTE` 分支**。

以下 Route 必须位于 App Guard 外部：

```text
/login
/logout
callback
其他 capabilityRootRoutes
```

否则 Login Guard 会保护 `/login` 自身，形成循环跳转。

正确 Route Tree：

```text
/
├── /login
├── /logout
├── ...capabilityRootRoutes
│
└── App Route Guards
      ↓
    PAGE_ROUTE
      ↓
    Layout
      ↓
    Page Routes
```

对应：

```tsx
const businessRoute: RouteObject = {
  path: PAGE_ROUTE,
  element: <Layout />,
  children: [
    ...createPageRoutes(appPageRoutes),
  ],
};

const guardedBusinessRoute =
  applyAppRouteGuards(businessRoute);

const routeList: RouteObject[] = [
  {
    path: '/',
    children: [
      // Capability Root Route 永远在 App Guard 外
      ...capabilityRootRoutes,

      // 仅主业务分支进入 App Guard
      guardedBusinessRoute,

      {
        index: true,
        element: (
          <Navigate
            to={resolveDefaultEntry()}
            replace
          />
        ),
      },
    ],
  },
];
```

---

### 5. Login + Authorization 的最终 Route Tree

例如：

```text
PageA：无 resourceKey
PageC：resourceKey = page_c_access
```

同时启用 Login 与 Authorization 后：

```text
/
├── login
├── logout
│
└── RequireLogin                         App Guard
      │
      └── page
           │
           └── Layout
                │
                ├── page_a
                │     └── PageA
                │
                └── AuthorizationGuard   Page Guard
                      │
                      └── page_c
                            │
                            └── PageC
```

两类 Guard 的底层实现完全一致：

```text
Guard Route
    ↓
条件判断
    ├── 通过 → Outlet
    └── 拒绝 → Navigate / Forbidden
```

区别只有：

```text
App Route Guard
→ 包住 PAGE_ROUTE 分支

Page Route Guard
→ 包住某一个具体 Page Route
```

---

### 6. 确定性约束

Route Guard 必须满足以下约束：

1. Guard 注册数组顺序即外层到内层顺序。
2. Guard 组合统一使用 `reduceRight`。
3. Strategy Registry 的执行顺序必须确定，生成后的 Guard 数组顺序不得依赖文件扫描顺序。
4. Page Guard Factory 返回 `undefined` 时直接跳过，不生成空 Route。
5. Guard Factory 不允许自行设置业务 `path`、`index` 或 `children`；这些字段统一由 Route Builder 生成。
6. App Guard 只能作用于 `PAGE_ROUTE` 主业务分支。
7. `capabilityRootRoutes` 永远位于 App Guard 外。
8. Page Guard 只能作用于对应 Page Route，不得修改兄弟 Route。
9. Guard 顺序必须通过自动化测试固定，避免 Capability 增加后改变既有嵌套关系。

最终统一算法为：

```text
Guard Registry
      ↓
确定性注册顺序
      ↓
reduceRight
      ↓
Guard Route + children
      ↓
最终 React Router Route Tree
```