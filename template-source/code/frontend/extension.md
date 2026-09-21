# 第一章 方案设计

## 1.1 改造目标

当前 `template-source/code/frontend/src` 已经是完整可运行的 React 工程，Login、Authorization 也已经通过 `Identity`、`Access` 等通用 Contract 与基础应用做了一定程度的解耦。

本次改造的目标不是引入运行时插件系统，而是将当前完整工程拆分为：

```text
Base
+
Selected Extensions
+
Static Assembly
=
最终 React 工程
```

最终达到：

1. Login、Authorization 以独立 Extension 形式维护。
2. 后续新增 Tracking、Layout Enhancement 等能力时，不需要修改 Base 业务代码。
3. Extension 只声明自身对全局应用的 Contribution，不直接 Patch `App.tsx`、Routes、Layout 等文件。
4. 模板组装发生在生成阶段，最终生成结果仍然是普通 React 工程。
5. 最终用户代码中不存在：

   * `extensions/`
   * `extension-runtime`
   * `ExtensionRegistry`
   * `extension.yaml`
   * `Contribution`
6. 当前 `template-source/code/frontend/src` 作为默认完整配置的组装结果，不再作为 Login / Authorization 的唯一维护源。

本阶段只解决前端 Extension 架构与静态组装，不设计能力管理 UI、版本升级工具等后续能力。

---

## 1.2 核心设计原则

### 1.2.1 Extension 是模板维护概念，不是应用运行时概念

Extension 只存在于模板维护阶段：

```text
Base
  +
Extension
  ↓
Assembly Compiler
  ↓
src/
```

最终 React 工程不得动态发现插件，也不使用：

```text
import.meta.glob('extensions/**')
Extension Registry
Runtime Extension Ordering
```

`import.meta.glob` 可以继续用于当前业务页面动态导入，但不能用于发现 Extension。

---

### 1.2.2 普通源码不定义 Extension Point

以下内容本身不是 Extension Point：

```text
API
Hook
Component
Type
Context
普通工具类
普通 CSS
```

它们只是 Extension 自身携带的源码。

例如 Login 的：

```text
apis/login.ts
hooks/useGuard.ts
pages/Login/**
providers/LoginProvider.tsx
```

直接复制进入最终 React 工程对应目录。

只有需要多个能力共同参与“全局组装”的位置，才定义 Extension Point。

---

## 1.3 V1 Extension Point

V1 固定支持以下五类 Extension Point：

```text
Provider
Root Route
Page Route
Initializer
Error Reporter
```

暂不开放通用 Layout Patch、任意 AST Patch、任意文件 Anchor。

### Provider

用于注册应用级 Provider。

当前对应：

```text
src/providers/AppProviders.tsx
```

例如：

```text
Login
  → LoginProvider

Authorization
  → AuthorizationProvider
```

---

### Root Route

用于注册不属于 `/page` 页面树的路由。

当前 Login 提供：

```text
/login
/logout
```

最终生成：

```text
src/routes/rootRoutes.tsx
```

---

### Page Route

用于 Extension 自身提供的普通页面。

例如 Authorization 提供：

```text
authorization_management
```

该页面继续使用现有统一链路：

```text
PageRouteDefinition
        ↓
resolvePageRouteTree
        ↓
createPageRoutes
        ↓
AccessBoundary
```

Authorization 不再维护自己的 RouteGuard 或 Menu Transform。

---

### Initializer

用于应用启动时执行初始化逻辑。

当前对应：

```text
src/bootstrap/appInitializers.ts
```

例如未来 Tracking Extension 可以注册：

```text
initTracking()
```

---

### Error Reporter

用于注册全局错误上报逻辑。

当前对应：

```text
src/observability/errorReporter.ts
```

`ErrorBoundary` 保持只依赖统一的：

```text
reportError()
```

而不知道具体 Tracking 实现。

---

## 1.4 模板维护目录设计

基于当前 `template-source/code/frontend/src` 拆分为：

```text
template-source/code/frontend/
│
├── base/
│   └── src/
│
├── extensions/
│   ├── login/
│   │   ├── extension.yaml
│   │   └── src/
│   │
│   └── authorization/
│       ├── extension.yaml
│       └── src/
│
├── assembly/
│   ├── extension.schema.json
│   └── assemble.mjs
│
└── src/
    └── ...
```

其中：

```text
base/src
```

是无 Login、无 Authorization 时仍可独立运行的基础应用。

```text
extensions/**
```

只属于模板开发者维护区。

```text
src/**
```

是 Assembly Compiler 的生成结果。

默认完整模板配置：

```text
Base
+
Login
+
Authorization
```

应能够重新生成当前 `src` 的业务能力。

`src` 后续原则上不直接维护 Extension 聚合关系。

---

## 1.5 Base 的职责

Base 负责稳定的平台能力和应用骨架。

当前以下能力应保留在 Base：

```text
App.tsx
index.tsx

layout/**

platform/identity/**
platform/access/**

routes/index.tsx
routes/routeBuilder.tsx
routes/pageRegistry.ts

utils/pageRouteTree.ts
utils/pageRoutes.tsx
utils/pageIdentity.ts
utils/route.tsx

hooks/usePageMenus.ts

components/ErrorBoundary/**

pages/Home/**

bootstrap/appInitializers.ts
observability/errorReporter.ts

通用 assets / styles / typings / utils
```

其中：

```text
Identity
Access
```

属于正式 Base Contract。

Base 可以提供：

```text
IdentityProvider
defaultAccess
AccessBoundary
useIdentity
useAccess
```

但不得知道：

```text
LoginProvider
AuthorizationProvider
AuthorizationManagement
```

等具体能力。

---

## 1.6 Login Extension 组织

Login Extension 建议组织为：

```text
extensions/login/
│
├── extension.yaml
│
└── src/
    ├── apis/
    │   └── login.ts
    │
    ├── context/
    │   └── LoginContext.tsx
    │
    ├── hooks/
    │   └── useGuard.ts
    │
    ├── pages/
    │   ├── Login/
    │   │   └── index.tsx
    │   └── Logout/
    │       └── index.tsx
    │
    ├── providers/
    │   └── LoginProvider.tsx
    │
    ├── constants/
    │   └── login.ts
    │
    └── typings/
        ├── login.ts
        └── yst.ts
```

当前：

```text
constants/yst.ts
```

也属于 Login 能力，应随 Login 迁移。

当前 `constants/index.ts` 中：

```text
CURRENT_URL
USER_INFO_KEY
AUTHORIZATION_KEY
```

需要按实际引用重新分类。

Login 专属常量迁移至：

```text
constants/login.ts
```

不要让 Base 保留认证能力的私有常量。

Login Extension 的 Contribution：

```yaml
id: login

requires: []

contributes:
  providers:
    - id: login.provider
      source: src/providers/LoginProvider.tsx
      export: LoginProvider

  rootRoutes:
    - id: login.login
      path: /login
      source: src/pages/Login/index.tsx
      export: default

    - id: login.logout
      path: /logout
      source: src/pages/Logout/index.tsx
      export: default
```

---

## 1.7 Authorization Extension 组织

Authorization 建议组织为：

```text
extensions/authorization/
│
├── extension.yaml
│
└── src/
    ├── apis/
    │   └── authorization.ts
    │
    ├── components/
    │   └── Authorization/
    │       └── Permission.tsx
    │
    ├── constants/
    │   └── resources.ts
    │
    ├── hooks/
    │   └── usePermission.ts
    │
    ├── pages/
    │   └── AuthorizationManagement/
    │       └── index.tsx
    │
    ├── providers/
    │   └── AuthorizationProvider.tsx
    │
    └── typings/
        ├── authorization.ts
        └── generated/
            └── authorizationApiTypes.ts
```

Authorization 依赖：

```yaml
requires:
  - login
```

该依赖用于：

```text
配置依赖解析
Extension 选择
Provider 装配顺序
```

不得被用于直接调用 Login Extension 私有实现。

Authorization Runtime 继续只通过：

```text
AccessContext
```

与 Base 交互。

Contribution：

```yaml
id: authorization

requires:
  - login

contributes:
  providers:
    - id: authorization.provider
      source: src/providers/AuthorizationProvider.tsx
      export: AuthorizationProvider

  pageRoutes:
    - id: authorization.management
      path: authorization_management
      label: 权限管理
      icon: SafetyCertificateOutlined
      resourceKey: system_authorization_management
```

---

## 1.8 Extension 内源码组织规则

Extension 的：

```text
src/**
```

必须直接镜像最终应用目录。

例如：

```text
extensions/login/src/apis/login.ts
```

最终固定输出：

```text
src/apis/login.ts
```

不再维护：

```text
source
target
```

逐文件映射配置。

Assembly Compiler 只执行：

```text
extension/src/<relativePath>
        ↓
src/<relativePath>
```

这样新增：

```text
Hook
Component
API
CSS
Type
```

都不需要增加新的注册项。

---

## 1.9 Extension 依赖约束

### 允许

Extension 可以依赖 Base Contract：

```text
Login
  → platform/identity

Authorization
  → platform/access
```

### 禁止

Extension 不允许直接依赖另一个 Extension 的私有代码。

例如 Authorization 禁止：

```ts
import { useLogin } from '@/context/LoginContext';
```

或者：

```ts
import { LoginProvider } from '@/providers/LoginProvider';
```

即使：

```text
authorization requires login
```

也不能形成源码层面的私有实现耦合。

---

## 1.10 Assembly 文件

以下文件定义为 Compiler 管理的静态 Assembly：

```text
src/providers/AppProviders.tsx
src/routes/rootRoutes.tsx
src/routes/systemPageRoutes.ts
src/bootstrap/appInitializers.ts
src/observability/errorReporter.ts
```

Extension 不允许直接修改这些文件。

---

## 1.11 Provider Assembly

Base 固有 Provider：

```text
IdentityProvider
```

Extension Provider 按依赖拓扑顺序装配。

完整配置：

```text
login
  ↓
authorization
```

应生成：

```tsx
<IdentityProvider>
  <LoginProvider>
    <AuthorizationProvider>
      {children}
    </AuthorizationProvider>
  </LoginProvider>
</IdentityProvider>
```

规则：

> 被依赖 Extension 在外层，依赖方在内层。

如果仅启用 Login：

```tsx
<IdentityProvider>
  <LoginProvider>
    {children}
  </LoginProvider>
</IdentityProvider>
```

Base-only：

```tsx
<IdentityProvider>
  {children}
</IdentityProvider>
```

---

## 1.12 Route Assembly

### Root Route

Compiler 根据 `rootRoutes` Contribution 生成：

```text
src/routes/rootRoutes.tsx
```

Login + Authorization 时：

```tsx
import Login from '@/pages/Login';
import Logout from '@/pages/Logout';

export const rootRoutes = [
  { path: '/login', element: <Login /> },
  { path: '/logout', element: <Logout /> },
];
```

Authorization 不注册 Root Route。

---

### Page Route

当前：

```text
constants/routes.tsx
```

同时包含：

```text
基础页面
Authorization 页面
业务页面写入区
```

需要进行拆分。

调整为：

```text
constants/routes.tsx
    → Base / 业务 Page

routes/systemPageRoutes.ts
    → Extension Page，Compiler 管理
```

`pageRegistry.ts` 改为：

```ts
export const APP_PAGE_ROUTES = [
  ...PAGE_ROUTES,
  ...SYSTEM_PAGE_ROUTES,
];
```

Authorization 开启时生成：

```ts
export const SYSTEM_PAGE_ROUTES = [
  {
    path: 'authorization_management',
    label: '权限管理',
    icon: 'SafetyCertificateOutlined',
    resourceKey: RESOURCES.SYSTEM.AUTHORIZATION_MANAGEMENT,
  },
];
```

没有 Extension Page 时：

```ts
export const SYSTEM_PAGE_ROUTES = [];
```

这样后续用户业务页面与 Extension 页面不会发生修改冲突。

---

## 1.13 Initializer Assembly

当前：

```ts
export async function runAppInitializers() {
  return Promise.resolve();
}
```

保留为 Assembly 文件。

无 Extension：

```ts
export async function runAppInitializers() {
  return Promise.resolve();
}
```

如果未来 Tracking 声明：

```yaml
initializers:
  - source: src/observability/initTracking.ts
    export: initTracking
```

则生成：

```ts
import { initTracking } from '@/observability/initTracking';

export async function runAppInitializers() {
  await initTracking();
}
```

---

## 1.14 Error Reporter Assembly

当前：

```text
ErrorBoundary
    ↓
reportError()
```

调用关系保留。

未来 Extension 只贡献 Reporter。

多个 Reporter 时：

```ts
export function reportError(error: Error, info?: unknown) {
  reporterA(error, info);
  reporterB(error, info);
}
```

Reporter 的异常不得阻止其他 Reporter 执行。

---

## 1.15 文件冲突规则

普通源码合并采用严格模式。

如果：

```text
Extension A
Extension B
```

同时提供：

```text
src/hooks/useXXX.ts
```

则：

```text
FILE_COLLISION
```

直接失败。

禁止：

```text
后加载覆盖前加载
```

也禁止通过 Extension 顺序解决普通文件冲突。

只有明确声明为 Assembly 的文件可以由多个 Extension 共同贡献。

---

## 1.16 Extension 依赖解析

Compiler 固定执行：

```text
读取配置
   ↓
选择 Extension
   ↓
计算 requires 闭包
   ↓
校验依赖
   ↓
拓扑排序
   ↓
复制源码
   ↓
聚合 Contribution
   ↓
生成 Assembly
```

至少校验：

```text
DUPLICATE_EXTENSION_ID
MISSING_EXTENSION_DEPENDENCY
CIRCULAR_EXTENSION_DEPENDENCY
INVALID_EXTENSION_DEFINITION
FILE_COLLISION
DUPLICATE_CONTRIBUTION_ID
DUPLICATE_ROUTE
```

当用户显式配置：

```text
login=false
authorization=true
```

应报配置冲突，不应静默将 Login 改成 true。

如果用户只表达：

```text
authorization=true
```

而没有显式关闭 Login，可由依赖解析自动补齐 Login。

---

## 1.17 当前兼容代码清理

当前以下代码属于上一阶段兼容层：

```text
components/Authorization/RouteGuard.tsx
utils/protectedRoutes.tsx
utils/route.tsx 中 createAuthorizedNavigation alias
```

其中已经明确标记 `@deprecated`。

本次改造需要先进行全局引用检查。

如果当前 `src` 已无实际调用：

```text
components/Authorization/RouteGuard.tsx
utils/protectedRoutes.tsx
createAuthorizedNavigation
```

则删除。

不要把这些兼容层继续带入 Extension 架构。

---

# 第二章 实施步骤

## 2.1 Step 1：冻结当前完整模板基线

首先以：

```text
template-source/code/frontend/src
```

当前代码为唯一基线。

执行：

```bash
pnpm build
```

确保当前完整模板构建通过。

同时记录完整配置场景：

```text
Login + Authorization
```

的以下行为：

```text
/login 可访问
/logout 可访问
/page 可访问
AuthorizationManagement 可访问
权限菜单过滤正常
AccessBoundary 正常
Permission 正常
```

本步骤禁止进行功能调整。

### 验收

```text
pnpm build PASS
```

并确保后续拆分前有稳定基线。

---

## 2.2 Step 2：清理当前废弃兼容层

全局搜索：

```text
RouteGuard
createProtectedRoutes
createAuthorizedNavigation
```

确认以下文件是否仍被使用：

```text
src/components/Authorization/RouteGuard.tsx
src/utils/protectedRoutes.tsx
```

如果只剩兼容导出，则删除。

同时删除：

```text
createAuthorizedNavigation
```

等无实际调用 alias。

### 验收

执行：

```bash
pnpm build
```

必须通过。

业务行为不得变化。

---

## 2.3 Step 3：建立 Base 目录

新增：

```text
template-source/code/frontend/base/src
```

从当前 `src` 中复制 Base 应保留的文件。

至少包括：

```text
App.tsx
index.tsx
index.css

assets/**
components/ErrorBoundary/**
layout/**

platform/identity/**
platform/access/**

pages/Home/**

routes/index.tsx
routes/pageRegistry.ts
routes/routeBuilder.tsx

utils/pageIdentity.ts
utils/pageRouteTree.ts
utils/pageRoutes.tsx
utils/route.tsx
utils/workbench.tsx

hooks/usePageMenus.ts

bootstrap/appInitializers.ts
observability/errorReporter.ts

styles/**
```

以及实际仍属于 Base 的通用文件。

不要机械按目录整体复制。

必须根据 import 依赖判断文件属于：

```text
Base
Login
Authorization
```

### Base 硬约束

完成后 Base 中不得出现业务实现 import：

```text
LoginProvider
AuthorizationProvider
LoginContext
AuthorizationManagement
usePermission
authorization API
YST
```

### 验收

通过静态搜索确认 Base 不依赖 Login / Authorization 私有实现。

---

## 2.4 Step 4：迁移 Login Extension

新增：

```text
extensions/login/
```

从当前 `src` 中迁移 Login 相关代码。

至少包括：

```text
src/apis/login.ts
src/context/LoginContext.tsx
src/hooks/useGuard.ts
src/pages/Login/**
src/pages/Logout/**
src/providers/LoginProvider.tsx
src/constants/yst.ts
src/typings/login.ts
src/typings/yst.ts
```

重新整理 Login 常量。

将：

```text
CURRENT_URL
USER_INFO_KEY
AUTHORIZATION_KEY
```

逐项检查用途。

属于 Login 的迁移至例如：

```text
extensions/login/src/constants/login.ts
```

并修正 Login 内 import。

如果某常量同时被多个非 Login 模块消费，应判断其是否属于 Base Contract，而不是机械迁移。

新增：

```text
extensions/login/extension.yaml
```

声明：

```text
Provider
/login
/logout
```

### 验收

Login Extension 内代码只能依赖：

```text
自身文件
Base 文件
第三方依赖
```

不得依赖 Authorization。

---

## 2.5 Step 5：迁移 Authorization Extension

新增：

```text
extensions/authorization/
```

迁移当前 Authorization 代码：

```text
src/apis/authorization.ts
src/components/Authorization/Permission.tsx
src/constants/resources.ts
src/hooks/usePermission.ts
src/pages/AuthorizationManagement/**
src/providers/AuthorizationProvider.tsx
src/typings/authorization.ts
src/typings/generated/**
```

如果 `authorization.css` 或其他权限专属资源仍存在实际引用，一并迁移。

新增：

```text
extensions/authorization/extension.yaml
```

声明：

```text
requires: login
Provider
AuthorizationManagement Page
```

不得声明：

```text
RouteGuard
MenuTransform
Layout Patch
```

### 验收

Authorization 页面权限仍通过：

```text
resourceKey
→ AccessBoundary
```

实现。

菜单权限仍统一通过：

```text
usePageMenus
→ useAccess
```

实现。

---

## 2.6 Step 6：调整 Page Route 聚合

从当前：

```text
src/constants/routes.tsx
```

移除：

```text
authorization_management
```

只保留 Base 页面及现有业务页面管理区域。

新增 Compiler Assembly：

```text
src/routes/systemPageRoutes.ts
```

默认完整配置应生成 Authorization 页面配置。

修改：

```text
src/routes/pageRegistry.ts
```

为：

```text
PAGE_ROUTES
+
SYSTEM_PAGE_ROUTES
```

统一进入：

```text
resolvePageRouteTree
```

### 验收

不得为 Authorization 新增特殊 RouteBuilder 分支。

以下链路保持唯一：

```text
PageRouteDefinition
→ APP_PAGE_ROUTES
→ resolvePageRouteTree
→ createAccessibleRoutes
```

---

## 2.7 Step 7：建立 Extension Schema

新增：

```text
assembly/extension.schema.json
```

V1 Schema 至少定义：

```text
id
requires
contributes.providers
contributes.rootRoutes
contributes.pageRoutes
contributes.initializers
contributes.errorReporters
```

Manifest 中不登记普通源码文件清单。

统一约定：

```text
extension/src/**
→
output/src/**
```

### 验收

Login、Authorization 两个 Manifest 均通过 Schema Validation。

---

## 2.8 Step 8：实现 Assembly Compiler

新增：

```text
assembly/assemble.mjs
```

V1 可以使用 Node 实现，负责前端模板自身的可重复组装验证。

不要在脚本中出现：

```text
if extension.id === 'login'
if extension.id === 'authorization'
```

Compiler 只能理解：

```text
Provider
RootRoute
PageRoute
Initializer
ErrorReporter
```

通用模型。

执行流程：

```text
1. 读取 Base
2. 读取 Extension Manifest
3. 根据配置选择 Extension
4. 解析 requires
5. 拓扑排序
6. 将 Base 输出到目标 src
7. 合并 Extension src/**
8. 检测普通文件冲突
9. 聚合 Contribution
10. 生成 Assembly 文件
11. 完成结构校验
```

---

## 2.9 Step 9：生成 AppProviders.tsx

由 Compiler 完整生成：

```text
src/providers/AppProviders.tsx
```

禁止使用文本 Anchor 在现有文件上插入。

Base-only：

```text
IdentityProvider
```

Login：

```text
IdentityProvider
  └─ LoginProvider
```

Login + Authorization：

```text
IdentityProvider
  └─ LoginProvider
       └─ AuthorizationProvider
```

Provider 顺序必须根据 Extension 拓扑排序生成。

### 验收

增加至少三个 Assembly 测试：

```text
[]
[login]
[login, authorization]
```

断言最终 Provider nesting。

---

## 2.10 Step 10：生成 rootRoutes.tsx

Compiler 根据 `rootRoutes` Contribution 完整生成：

```text
src/routes/rootRoutes.tsx
```

无 Login：

```ts
export const rootRoutes = [];
```

有 Login：

```text
/login
/logout
```

### 验收

检测：

```text
重复 path
重复 Contribution ID
source 文件不存在
export 无法解析
```

至少在 Assembly 阶段提供明确错误。

---

## 2.11 Step 11：生成 systemPageRoutes.ts

Compiler 根据 `pageRoutes` Contribution 生成：

```text
src/routes/systemPageRoutes.ts
```

Authorization 开启时：

```text
authorization_management
```

关闭时为空。

同时校验：

```text
authorization_management
        ↓
src/pages/AuthorizationManagement/index.tsx
```

页面模块必须存在。

该映射应复用当前：

```text
pageDirectoryFromPath()
```

规则，不要在 Compiler 中重新定义另一套命名规则。

---

## 2.12 Step 12：实现 Initializer / ErrorReporter Assembly

将当前：

```text
bootstrap/appInitializers.ts
observability/errorReporter.ts
```

纳入 Compiler 管理。

当前 Login / Authorization 暂时可以没有这两类 Contribution。

目标是保证未来增加 Extension 时不需要修改：

```text
index.tsx
ErrorBoundary
```

### 验收

无 Contribution 时生成的实现必须仍然可编译、可运行。

---

## 2.13 Step 13：生成默认完整 src

使用默认配置：

```text
login=true
authorization=true
```

执行 Assembly Compiler。

重新生成：

```text
template-source/code/frontend/src
```

之后：

```text
src
```

应成为：

> Base + Login + Authorization 的确定性组装结果。

不得再人工手动维护：

```text
AppProviders.tsx
rootRoutes.tsx
systemPageRoutes.ts
appInitializers.ts
errorReporter.ts
```

---

## 2.14 Step 14：组合场景验证

必须分别生成和构建以下场景。

### Base-only

```text
extensions=[]
```

要求：

```text
pnpm build PASS
/page 可正常进入
Home 页面正常
无 /login
无 AuthorizationManagement
Access 默认允许
```

---

### Login

```text
extensions=[login]
```

要求：

```text
pnpm build PASS
/login 存在
/logout 存在
LoginProvider 存在
无 AuthorizationProvider
无 AuthorizationManagement
```

---

### Login + Authorization

```text
extensions=[login, authorization]
```

要求：

```text
pnpm build PASS
LoginProvider 外层
AuthorizationProvider 内层

/login
/logout
authorization_management

菜单权限过滤正常
页面直接访问权限正常
Permission 组件正常
```

---

### 非法 Authorization-only

显式：

```text
login=false
authorization=true
```

要求失败，并返回明确：

```text
MISSING_EXTENSION_DEPENDENCY
```

或者：

```text
EXPLICIT_EXTENSION_CONFLICT
```

禁止生成半可用工程。

---

## 2.15 Step 15：增加确定性与冲突测试

相同：

```text
Base
配置
Extension 版本
```

重复 Assembly 两次，输出内容必须一致。

不得包含：

```text
随机 ID
当前时间
非确定性文件顺序
```

增加至少以下测试：

```text
Duplicate Extension ID
Circular Dependency
Missing Dependency
Duplicate Route
Duplicate Contribution
File Collision
Missing Source
Invalid Manifest
```

---

## 2.16 Step 16：最终代码约束检查

最终生成的：

```text
template-source/code/frontend/src
```

中不得出现：

```text
extension.yaml
extensions/
ExtensionRegistry
Contribution
ExtensionRuntime
assemble
```

用户最终仍然看到普通 React 工程：

```text
src/
├── apis
├── components
├── constants
├── context
├── hooks
├── layout
├── pages
├── platform
├── providers
├── routes
├── styles
├── typings
└── utils
```

Extension 只是模板工程内部维护方式。

---

## 2.17 本阶段完成标准

本次改造完成后，应满足以下结果：

```text
当前完整前端代码
      ↓
拆分
      ↓
Base + Login + Authorization
      ↓
通用 Assembly Compiler
      ↓
重新生成相同能力的普通 React 工程
```

并且后续增加一个新的 Extension 时：

```text
新增 extension.yaml
新增 extension/src/**
```

如果现有五类 Extension Point 已能覆盖需求，则：

```text
无需修改 Base
无需修改 Assembly Compiler
无需增加 Anchor
无需增加 Capability 专属判断
```

只有出现现有五类 Extension Point 无法表达的新型全局组合需求时，才允许评估新增新的 Extension Point。
