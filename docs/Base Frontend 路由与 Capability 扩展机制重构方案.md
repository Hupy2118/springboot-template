# Base Frontend 路由与 Capability 扩展机制重构方案

## 一、方案目标

本次调整针对：

```text
template-source/base/frontend/src
```

重点解决当前 Base Frontend 中存在的以下问题：

1. Route、Menu、Capability 扩展之间职责边界不清晰。
2. Login、Authorization 被抽象成不同形式的 Wrapper / Guard，概念不统一。
3. `PAGE_ROUTES` 与 `capabilityPageRoutes` 在多个位置重复组合。
4. Page Route 类型与 Menu 类型耦合。
5. 存在历史遗留文件、无效代码和 Capability 能力泄漏到 Base 的情况。
6. Template Engine 的扩展入口命名不能准确表达职责。

本次重构原则：

```text
Route 是页面定义的唯一事实来源
        ↓
Router Builder 生成 React Router
        ↓
Menu Projection 生成菜单

Capability 不建立第二套路由/菜单体系，
只通过明确的扩展点参与 Route、Guard、Menu Transform、Provider。
```

---

# 二、问题 1：菜单扩展机制重新定义

## 2.1 当前问题

当前：

```text
src/capability-extensions/menus.ts
```

包含：

```ts
export const useCapabilityMenus = (menus: Route[]): Route[] => {
  let current = menus;

  // devagentstudio:capability-menu-transforms

  return current;
};
```

容易让人理解成：

> Capability 又维护了一套菜单。

但实际上不是。

菜单仍然由：

```text
PAGE_ROUTES
+
capabilityPageRoutes
```

生成：

```text
Page Route Definition
        ↓
createLayoutMenus()
        ↓
Menu
```

`menus.ts` 只是允许 Authorization 等 Capability 对已经生成的菜单做二次处理。

例如 Authorization：

```ts
current = useAuthorizationMenuTransform(current);
```

根据用户权限隐藏无权菜单。

因此该机制应该保留，但重新命名。

---

## 2.2 最终设计

文件继续保留在：

```text
src/capability-extensions/
```

而不是放到：

```text
src/hooks/
```

原因：

```text
目录表达架构职责：
capability-extensions = Template Engine Capability 注入面

函数名称表达 React 运行语义：
useXXX = React Hook
```

建议：

```text
menus.ts
```

可进一步改成：

```text
menuTransforms.ts
```

内容：

```ts
export const useCapabilityMenuTransforms = (
  menus: AppMenuItem[],
): AppMenuItem[] => {
  let current = menus;

  // devagentstudio:capability-menu-transforms

  return current;
};
```

Layout：

```ts
const menus = useCapabilityMenuTransforms(
  createLayoutMenus(appPageRoutes, PAGE_BASE_PATH),
);
```

最终链路：

```text
Page Routes
    ↓
Menu Projection
    ↓
Capability Menu Transforms
    ↓
ProLayout
```

### 结论

不要删除菜单 Capability 扩展入口。

修改：

```text
useCapabilityMenus
        ↓
useCapabilityMenuTransforms
```

推荐同时修改文件：

```text
menus.ts
        ↓
menuTransforms.ts
```

如果修改文件路径，需要同步修改：

```text
template-source/strategy-registry-v2.yaml
BaseSurfaceContractTest
Capability 相关 Contract Test
```

---

# 三、问题 2：统一 Route Guard 模型

## 3.1 当前问题

当前 Capability Route 中存在：

```ts
capabilityPageWrappers = [
  wrapLoginRequired,
  wrapAuthorizationPage,
];
```

Base Runtime 再执行：

```ts
wrapCapabilityPage(element, page)
```

最终形成类似：

```tsx
<LoginRequired>
  <RouteGuard resourceKey="page_c_access">
    <PageC />
  </RouteGuard>
</LoginRequired>
```

这里存在两个问题。

第一，Login 实际保护的是整个业务路由区域：

```text
/page/**
```

而不是每个 Page。

第二，Authorization 本质也是 Route Guard，却被建模成：

```text
Page Wrapper
```

导致出现：

```text
LoginRequired
RouteGuard
wrapLoginRequired
wrapAuthorizationPage
capabilityPageWrappers
wrapCapabilityPage
```

多个概念描述同一类问题。

---

# 四、统一为 Route Guard

核心原则：

> Login 和 Authorization 都是 Route Guard，底层统一采用 Guard Route + Outlet。  
> App/Page 只是 Guard 插入路由树的位置不同。

最终模型：

```text
Route Guard
│
├── App Route Guard
│     └── Login
│
└── Page Route Guard
      └── Authorization
```

技术实现统一：

```tsx
Guard
   ↓
condition
   ├── pass
   │     ↓
   │   <Outlet />
   │
   └── reject
         ↓
      Navigate / Forbidden
```

---

# 五、App Route Guard：Login

Login Capability 提供：

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

它不再包装：

```tsx
<PageA />
<PageB />
<PageC />
```

而是直接挂载到整个业务 Route Tree：

```text
RequireLogin
      ↓
    /page
      ↓
    Layout
      ↓
PageA / PageB / PageC
```

---

# 六、Page Route Guard：Authorization

Authorization Guard 同样使用 Outlet：

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

对于：

```ts
{
  pageId: 'page_c',
  resourceKey: 'page_c_access',
}
```

生成的 Route Tree 是：

```text
page_c
   ↓
AuthorizationGuard
   ↓
PageC
```

而不是：

```tsx
<AuthorizationGuard>
  <PageC />
</AuthorizationGuard>
```

---

# 七、最终 Route Tree

同时开启：

```text
login
authorization
```

且：

```text
PageA 无权限控制
PageC 有 resourceKey
```

最终应该形成：

```text
/
├── login
├── logout
│
└── RequireLogin                     App Route Guard
      │
      └── page
           │
           └── Layout
                │
                ├── page_a
                │     └── PageA
                │
                └── page_c
                      │
                      └── AuthorizationGuard    Page Route Guard
                            │
                            └── PageC
```

对应 React Router 概念：

```tsx
{
  element: <RequireLogin />,
  children: [
    {
      path: 'page',
      element: <Layout />,
      children: [
        {
          path: 'page_a',
          element: <PageA />,
        },
        {
          path: 'page_c',
          element: (
            <AuthorizationGuard
              resourceKey="page_c_access"
            />
          ),
          children: [
            {
              index: true,
              element: <PageC />,
            },
          ],
        },
      ],
    },
  ],
}
```

---

# 八、Route Guard 注册方式

虽然底层实现完全一样，但注册入口应该根据 Scope 分开。

建议：

```text
src/capability-extensions/
├── providers.tsx
├── routes.tsx
├── routeGuards.tsx
└── menuTransforms.ts
```

其中：

```tsx
export const appRouteGuards = [
  // devagentstudio:app-route-guards
];
```

Login 开启后：

```tsx
export const appRouteGuards = [
  RequireLogin,

  // devagentstudio:app-route-guards
];
```

Page Guard 不建议简单注册 Component，因为 Authorization 还需要读取当前 Page 的：

```text
resourceKey
```

因此定义统一的 Guard Factory：

```ts
export type PageRouteGuardFactory = (
  page: PageRouteDefinition,
) => RouteObject | undefined;
```

注册：

```ts
export const pageRouteGuards: PageRouteGuardFactory[] = [
  // devagentstudio:page-route-guards
];
```

Authorization 提供：

```tsx
export function createAuthorizationRouteGuard(
  page: PageRouteDefinition,
): RouteObject | undefined {
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

这样：

```text
App Guard
Page Guard
```

最终都交给 Route Builder 处理成：

```text
RouteObject + children + Outlet
```

只是 Scope 不同。

---

# 九、删除当前 Page Wrapper 体系

以下概念全部删除：

```text
capabilityPageWrappers
wrapCapabilityPage
wrapLoginRequired
wrapAuthorizationPage
```

包括：

```text
src/routes/capabilityRuntime.tsx
```

中：

```ts
wrapCapabilityPage()
```

如果 `capabilityRuntime.tsx` 只剩：

```ts
capabilityEntryPath
```

则无需再保留该 Runtime 文件，可以把该逻辑放到 Route Registry / Route Builder 中。

Authorization：

```text
wrapAuthorizationPage
```

改成：

```text
AuthorizationGuard
+
createAuthorizationRouteGuard
```

Login：

```text
wrapLoginRequired
LoginRequired
```

改成：

```text
RequireLogin
```

---

# 十、Strategy Registry 同步调整

当前 Login：

```text
frontend.login.import-page-wrapper
frontend.login.register-page-wrapper
```

删除。

对应：

```text
capability-page-wrappers
```

中的：

```ts
wrapLoginRequired,
```

也删除。

新增：

```text
frontend.login.import-app-route-guard
frontend.login.register-app-route-guard
```

目标：

```text
devagentstudio:app-route-guards
```

注入：

```ts
RequireLogin,
```

Authorization 当前：

```text
frontend.authorization.import-page-wrapper
frontend.authorization.register-page-wrapper
```

同样改成：

```text
frontend.authorization.import-page-route-guard
frontend.authorization.register-page-route-guard
```

目标：

```text
devagentstudio:page-route-guards
```

注册：

```ts
createAuthorizationRouteGuard,
```

最终 Strategy Registry 的语义从：

```text
Login
 └── Page Wrapper

Authorization
 └── Page Wrapper
```

改成：

```text
Login
 └── App Route Guard

Authorization
 └── Page Route Guard
```

---

# 十一、问题 3：统一 PAGE_ROUTES 与 capabilityPageRoutes 的聚合位置

`PAGE_ROUTES` 和 `capabilityPageRoutes` 不应该合并成同一个源。

它们分别属于：

```text
PAGE_ROUTES
→ DevAgent Studio Business Route Projection

capabilityPageRoutes
→ Template Capability Contribution
```

所以两个源都应该保留。

但当前存在真正的重复逻辑：

```ts
[...PAGE_ROUTES, ...capabilityPageRoutes]
```

同时出现在：

```text
routes/index.tsx
layout/index.tsx
```

这必须收敛。

新增：

```text
src/routes/pageRegistry.ts
```

例如：

```ts
export const appPageRoutes: PageRouteDefinition[] = [
  ...PAGE_ROUTES,
  ...capabilityPageRoutes,
];
```

之后：

```text
Router
Menu
Default Route
Page Guard
```

全部消费：

```text
appPageRoutes
```

最终：

```text
PAGE_ROUTES
          ┐
          ├── appPageRoutes
          │       ├── Router Builder
Capability│       ├── Menu Projection
PageRoutes┘       └── Default Page Selection
```

避免未来：

```text
Router 一套排序
Menu 一套排序
```

产生偏差。

---

# 十二、Route / Menu 类型拆分

当前：

```ts
PageRouteDefinition
    extends / derives from
Route(MenuDataItem)
```

是不合理的。

这导致：

```text
Router Metadata
Menu Metadata
Capability Metadata
```

混在一个类型中。

尤其：

```ts
[key: string]: any
```

基本失去了类型约束。

建议拆成：

```ts
export interface PageRouteDefinition {
  name: string;

  pageId: string;

  resourceKey?: string;

  hideInMenu?: boolean;

}
```

独立定义：

```ts
export interface AppMenuItem {
  key: string;
  name: string;
  path?: string;
  icon?: ReactNode;
  hideInMenu?: boolean;
  children?: AppMenuItem[];
}
```

然后：

```text
PageRouteDefinition
        ↓
Menu Projection
        ↓
AppMenuItem
```

Menu 不再反过来决定 Route 数据结构。

---

# 十三、统一主 Layout 页面加载约定

Base 中只有一种主 Layout Page Definition：

```text
{ name, pageId, resourceKey? }
```

业务、系统和 Capability 页面全部遵循：

```text
pageId
  ↓
src/pages/<PascalCase(pageId)>/index.tsx
  ↓
/page/<kebab-case(pageId)>
  ↓
lazy module
```

因此删除 `modulePath`、`component`、`routeId` 与内部页面的 `path` 例外。Capability 不再为页面组件注入 import；它只在 `capabilityPageRoutes` 中注册 Route Fact，并通过 Addition 提供符合 `pageId` 映射的页面文件。

---

# 十四、修复默认页计算逻辑

Route Builder、Menu Projection 与 Default Page 都只识别 `pageId`，不允许存在第二种页面模块或 URL 解析规则。

应该统一建立：

```ts
resolvePagePath(page)
```

例如：

```ts
export function resolvePagePath(
  page: PageRouteDefinition,
): string
```

供：

```text
Route Builder
Menu Projection
findFirstPagePath
```

共同使用。

避免三处分别拼 URL。

外部链接不是主 Layout Page Definition；如需展示，应作为独立的 Menu Transform 输出，而不能进入 `appPageRoutes`。

---

# 十五、路由相关最终目录建议

本轮尽量不要为了“漂亮”做大量目录移动。

推荐控制在：

```text
src/
├── capability-extensions/
│   ├── providers.tsx
│   ├── routes.tsx
│   ├── routeGuards.tsx
│   └── menuTransforms.ts
│
├── routes/
│   ├── index.tsx
│   ├── pageRegistry.ts
│   └── routeBuilder.tsx
│
├── utils/
│   ├── pageIdentity.ts
│   └── menuProjection.ts
│
├── typings/
│   ├── routes.ts
│   └── menu.ts
```

其中：

```text
routes.tsx
→ Capability 增加 Route Definition

routeGuards.tsx
→ Capability 增加 Route Guard

menuTransforms.ts
→ Capability 修改最终 Menu

providers.tsx
→ Capability 增加 Runtime Provider
```

四个扩展面职责非常清楚。

---

# 十六、Base 中 Capability CSS 泄漏问题

当前：

```text
src/index.css
```

存在大量：

```text
.authorization-*
.authorization-login-*
```

样式。

这违反：

```text
Base
+
Optional Capability
```

的设计。

Base 不应该提前包含：

```text
Login
Authorization
```

专属样式。

调整：

```text
authorization Capability
└── authorization.css

login Capability
└── login.css
```

Capability Enable 时由 Template Engine 引入对应 CSS。

Base `index.css` 只保留：

```text
Tailwind
Antd layer
全局基础样式
Theme Token
```

---

# 十七、历史文件清理

以下属于高概率可删除项，但删除前需要全仓引用确认。

## 17.1 可直接重点回检

```text
src/apis/auth.ts
```

当前为空文件。

如果不是 Template Engine Target：

```text
直接删除。
```

---

```text
src/apis/welcome.ts
```

Welcome 页面当前没有调用。

如果没有生成代码依赖：

```text
删除。
```

---

```text
src/constants/index.ts
```

如果只有：

```ts
ENV_TAG = 'DEV'
```

且无引用：

```text
删除。
```

---

```text
src/typings/index.ts
```

若只有通用：

```text
Option
```

且无引用：

```text
删除。
```

---

## 17.2 AppIcon 体系

回检：

```text
src/assets/appIconList
src/layout/components/AppIcon
src/constants/layout.ts
```

当前 Layout 主链没有明显消费。

如果 DevAgent Studio 生成代码也不依赖：

```text
整套删除。
```

不要只删一个文件留下资源。

---

## 17.3 utils/workbench.tsx

包含：

```text
renderIcon
openNewPage
```

很像旧 Workbench 遗留能力。

如果新 Layout 不使用：

```text
删除。
```

但是需要同步确认外部链接支持。

当前如果仍支持：

```text
isUrl
```

那么菜单点击逻辑应该明确：

```text
内部 Route
→ navigate()

外部 URL
→ window.open / <a target>
```

不能一边保留 `isUrl`，一边所有菜单都执行：

```ts
navigate(item.path)
```

---

# 十八、样式体系清理

当前同时存在：

```text
Tailwind / index.css
LESS
旧 Theme Variables
```

不能直接认为所有 Less 无用，因为：

```text
variable.less
```

通过 Vite：

```text
additionalData
```

全局注入。

因此分两步：

### 第一步

确认 DevAgent Studio 生成页面是否仍生成：

```text
*.less
*.module.less
```

### 第二步

如果新生成规范已经全部改为 Tailwind/CSS：

删除：

```text
styles/index.less
styles/theme.less
styles/variable.less
Vite less additionalData
less dependency
module.less declarations
```

否则只删除确认无消费的：

```text
index.less
theme.less
```

保留：

```text
variable.less
```

直到生成体系迁移完成。

同时必须解决当前两个 Primary Color 不一致的问题：

```text
Tailwind
#2c68ff

旧 Less Theme
#725ae3
```

Base 只应保留一个主题事实来源。

---

# 十九、index.tsx 中遗留 Tracking 代码

当前存在大量注释掉的 Tracking 初始化代码。

这类代码不应该留在 Base。

原则：

```text
Tracking 是 Capability
而不是 Base 注释代码
```

因此：

```text
直接删除注释代码。
```

未来需要 Tracking：

```text
tracking Capability
    ↓
Provider / Init Extension
```

单独接入。

---

# 二十、service.ts 清理

`src/apis/service.ts` 主体仍然是 Base 必要能力，保留。

但需要回检：

```ts
all(axiosInstances: AxiosInstance[])
```

如果内部：

```ts
axios.all(axiosInstances)
```

类型/语义并不合理。

`axios.all()` 应该处理：

```text
Promise
```

而不是：

```text
AxiosInstance
```

如果无调用：

```text
删除 all()
```

优先不要保留无意义的公共 API。

---

# 二十一、实施顺序

建议按以下顺序实施，而不是一次性大量删文件。顺序的前提是：先建立页面 Route 的唯一事实来源，再由 Route Builder 基于确定性 Guard Factory 构造 Route Tree，最后才迁移 Template Engine 注入面和清理历史资产。

每个 Phase 完成后都应至少执行：

```sh
mvn -f template-engine/pom.xml verify
./scripts/ci/verify-base-frontend.sh
git diff --check
```

涉及 Template Source 契约变更的 Phase，还必须验证 Base、Base + Login、Base + Authorization、Base + Login + Authorization 四种生成组合；涉及 Guard 顺序的测试不得依赖文件扫描顺序。

---

## Phase 0：现状盘点与迁移契约冻结

在修改 Runtime 前，完成全仓引用与生成链路盘点，覆盖：

```text
template-source/base/base.yaml
template-source/strategy-registry-v2.yaml
template-source/capabilities/*/capability-v2.yaml
Template Engine / Capability Authoring / Engine Service 测试
Template Source 文档、Generator、Skill、Route Projector
```

明确并写入测试的迁移边界：

```text
PAGE_ROUTES + capabilityPageRoutes
→ 只允许在 appPageRoutes 聚合

PageRouteDefinition
→ 仅表达页面事实；不得再继承 Menu 类型

Menu
→ 由 Page Route 投影产生，再由 Capability Transform 二次处理

Route Guard
→ 只描述 element；不得自行设置 path、index 或 children
```

确认所有进入主 Layout 的页面均可收敛为 `name`、`pageId`、可选 `resourceKey` 与菜单展示元数据。外部链接不属于 Page Definition；如需展示，只能由 Menu Transform 单独产生。

---

## Phase 1：路由模型收敛

完成：

```text
src/routes/pageRegistry.ts
    appPageRoutes = [...PAGE_ROUTES, ...capabilityPageRoutes]

PageRouteDefinition / AppMenuItem 拆分
resolvePagePath(page)
Menu Projection
Default Page Selection
```

实施要求：

1. Router、Menu、Default Page、Page Guard 都只消费 `appPageRoutes`。
2. `resolvePagePath(page)` 成为内部页面 URL 的唯一解析入口；不得由各消费方分别拼接 `PAGE_ROUTE`、`pageId` 或维护 `path` 覆盖。
3. `PageRouteDefinition` 不含 `component`、`modulePath`、`routeId`、`path` 或 `isUrl`。所有页面均由 `pageId → 目录 → path → lazy module` 加载。
4. 保留 `PAGE_ROUTES` 与 `capabilityPageRoutes` 两个来源；它们的职责不同，不合并为同一份受管配置。

本 Phase 不改变 Guard 行为，只消除 Route、Menu、默认页之间的重复聚合和不一致的路径规则。

验收：删除 `routes/index.tsx`、`layout/index.tsx` 中各自的 `[...PAGE_ROUTES, ...capabilityPageRoutes]`；业务、系统和 Capability 页面均可按 `pageId` 路由、显示菜单并正确参与默认页计算。

---

## Phase 2：Route Guard 重构

新增：

```text
src/capability-extensions/routeGuards.tsx
    RouteGuardDefinition
    AppRouteGuardFactory
    PageRouteGuardFactory
    appRouteGuards
    pageRouteGuards

src/routes/routeBuilder.tsx
    createPageRoutes
    applyPageRouteGuards
    applyAppRouteGuards
```

统一 Factory 契约：

```ts
export interface RouteGuardDefinition {
  element: ReactNode;
}

export type PageRouteGuardFactory = (
  page: PageRouteDefinition,
) => RouteGuardDefinition | undefined;

export type AppRouteGuardFactory = () =>
  RouteGuardDefinition | undefined;
```

组合算法必须固定为：注册数组前到后等于 Route Tree 外到内，并一律使用 `reduceRight`。Route Builder 先构造最终业务 Page Route，再由 `applyPageRouteGuards(page, pageRoute)` 包装；Factory 返回 `undefined` 时直接跳过，不生成空 Guard Route。

App Guard 使用相同算法，但只能包装主业务 `PAGE_ROUTE` 分支。`/login`、`/logout`、callback 和全部 `capabilityRootRoutes` 必须始终处于 App Guard 外，避免 Login Guard 保护 `/login` 而导致循环跳转。

验收：添加自动化测试固定两个以上 Guard 的嵌套顺序、无 `resourceKey` 页面跳过 Authorization Guard、Root Route 未受 App Guard 包装，以及 App Guard 不改变兄弟 Route。

---

## Phase 3：Login 与 Authorization 迁移到 Guard Factory

删除旧 Page Wrapper 体系：

```text
capabilityPageWrappers
wrapCapabilityPage
wrapLoginRequired
wrapAuthorizationPage
src/routes/capabilityRuntime.tsx
```

新增并迁移：

```text
Login
    RequireLogin + createLoginRouteGuard
    → appRouteGuards

Authorization
    AuthorizationGuard + createAuthorizationRouteGuard(page)
    → pageRouteGuards
```

`RequireLogin` 与 `AuthorizationGuard` 都以 `Guard Route + Outlet` 实现：通过时渲染 `<Outlet />`，拒绝时分别跳转登录页或显示 Forbidden。Authorization Factory 仅在 `page.resourceKey` 存在时返回 Guard 定义。

验收：Login 不再逐页面包装；同时开启 Login 和 Authorization 时，Route Tree 为：

```text
capabilityRootRoutes（含 login / logout）
    与 App Guard 同级

RequireLogin
    ↓
PAGE_ROUTE / Layout
    ↓
AuthorizationGuard（仅 resourceKey 页面）
    ↓
Page
```

---

## Phase 4：Menu Transform 与 Template Extension Surface 迁移

修改：

```text
menus.ts
        ↓
menuTransforms.ts

useCapabilityMenus
        ↓
useCapabilityMenuTransforms
```

Layout 只执行：

```text
appPageRoutes
    ↓
createLayoutMenus()
    ↓
useCapabilityMenuTransforms()
```

Capability Menu Transform 仍采用静态源码注入的 Hook 调用；不能改为动态 `.map()` 或 `.reduce()`，以保证 Hook 调用顺序稳定。

随后同步修改 Template Source 契约：

```text
base.yaml
strategy-registry-v2.yaml 的 target、anchor、strategy、managedMarker
login / authorization capability-v2.yaml 的 existingTargets 与 additions
BaseSurfaceContractTest
Capability Authoring、Engine Service 与 Capability Contract Test
Base frontend project-structure 文档
```

Strategy Registry 中 Login 的 Page Wrapper Strategy 改为 App Route Guard Strategy，Authorization 的 Page Wrapper Strategy 改为 Page Route Guard Strategy。Guard 注入的 Strategy `order` 必须直接决定生成后 `appRouteGuards`、`pageRouteGuards` 的数组顺序。

验收：单独开启、同时开启、重复 apply、remove / refresh 都生成确定且可重放的 Guard 注册顺序；Base 的空 Capability 生成结果仍等于 Base。

---

## Phase 5：Capability 边界清理

在 Capability 生成路径已验证后，移动：

```text
Base index.css 中的 authorization-* / authorization-login-* 样式
    ↓
authorization Capability CSS

Login 专属样式
    ↓
login Capability CSS
```

先定义并验证 Capability CSS 在生成工程中的引入入口，再从 Base 删除对应样式；不得留下“Capability 已启用但样式未加载”的中间状态。

同时删除：

```text
Base index.tsx 中注释掉的 Tracking 初始化代码
```

Tracking 如需恢复，应作为独立 Capability 通过 Provider / Init Extension 接入。

验收：未启用 Login / Authorization 的 Base 不含其专属 CSS；启用后前端构建通过且 Capability 页面样式正确。

---

## Phase 6：历史文件删除

通过全仓搜索和生成组合验证后，按依赖关系删除：

```text
modulePath
systemPageModules / modulePath resolve
apis/auth.ts
apis/welcome.ts
constants/index.ts
typings/index.ts
AppIcon
appIconList
constants/layout.ts
utils/workbench.tsx
确认无消费的旧 LESS 与 Vite less 配置
```

`modulePath` 的清理必须晚于 Phase 1：先确认 Generator、Skill、Fixture、Capability Authoring 测试和外部 Template Source 均不再生成或消费它。旧 LESS 必须在确认 DevAgent Studio 页面不再生成 `*.less` / `*.module.less` 后才可连同 `variable.less`、Vite `additionalData` 和 less dependency 一并删除；否则仅删除已证实无引用的文件，并保留兼容路径。

`utils/workbench.tsx` 删除前还必须确认外链菜单改为明确的链接渲染或 `window.open` 行为，而不是统一执行 `navigate(item.path)`。

不允许仅通过 Base Runtime 当前没 import 就直接删除，需要同时检查：

```text
Template Engine
Generator
Skill
Contract Test
Route Projector
Capability Source
```

验收：全仓不存在旧 Wrapper、`modulePath` 和失效文件引用；Base 只保留一个主题事实来源，前端构建与四种 Capability 组合测试全部通过。

---

# 二十二、最终架构

本轮调整后，前端 Base 的主链应该收敛为：

```text
                 PAGE_ROUTES
                      │
       capabilityPageRoutes
                      │
                      ▼
                appPageRoutes
                      │
          ┌───────────┴────────────┐
          │                        │
          ▼                        ▼
    Router Builder           Menu Projection
          │                        │
          │                        ▼
          │            useCapabilityMenuTransforms
          │                        │
          ▼                        ▼
 React Router Tree             ProLayout
          │
          ▼
 App Route Guard
   RequireLogin
          │
          ▼
       Layout
          │
          ▼
 Page Route Guard
 AuthorizationGuard
          │
          ▼
        Page
```

Capability 扩展面最终只保留四类：

```text
providers
    → 应用 Runtime Context

routes
    → Capability 新增页面/独立路由

routeGuards
    → Login / Authorization 等路由访问控制

menuTransforms
    → 菜单可见性等最终投影处理
```

这样 Base Frontend 的职责会明显比现在清晰。

---

# 二十三、本次重构的核心验收标准

重构完成后至少满足：

1. `PAGE_ROUTES + capabilityPageRoutes` 只在一个地方聚合。
2. Router 和 Menu 共同消费 `appPageRoutes`。
3. 不再存在 `capabilityPageWrappers`。
4. 不再存在 `wrapCapabilityPage`。
5. Login 使用：
   ```text
   App Route Guard + Outlet
   ```
6. Authorization 使用：
   ```text
   Page Route Guard + Outlet
   ```
7. Login 不再逐页面包装。
8. 无 `resourceKey` 页面不创建 Authorization Guard Route。
9. Menu 仍由 Route 投影生成。
10. Capability Menu Extension 仅承担 Transform。
11. `PageRouteDefinition` 不再继承 Menu 类型。
12. Base 不包含 Login / Authorization 专属 CSS。
13. Capability Extension Surface 与 Strategy Registry 一一对应。
14. Capability enable 后生成工程可正常执行：
   ```text
   frontend build
   ```
15. 以下组合全部有自动化测试：
   ```text
   Base
   Base + Login
   Base + Authorization
   Base + Login + Authorization
   ```

## 最终结论

本次前端重构不应该围绕“删除几个看起来重复的文件”展开，而应该先收敛三个核心模型：

```text
Route
Route Guard
Menu Projection
```

其中：

```text
Route
→ 页面事实来源

Route Guard
→ 路由访问控制
   ├── App Scope：Login
   └── Page Scope：Authorization

Menu
→ Route 的投影结果
   + Capability Transform
```

在这个模型确定以后，当前 `capabilityPageWrappers`、`wrapCapabilityPage`、`wrapLoginRequired` 等中间抽象自然就可以删除，剩余历史文件也更容易判断是否真正有存在价值。
