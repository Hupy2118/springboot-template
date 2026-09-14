# Capability 前端 Extension Surface 拆分实施方案

## 1. 改造目标

当前 `frontend/src/generated` 中同时包含：

* Capability 开发者需要修改的扩展点；
* Base 内部运行与组装逻辑。

例如 `capabilityRoutes.tsx` 中：

```text
开发者需要关注：
- capabilityRootRoutes
- capabilityPageRoutes
- capabilityPageWrappers

开发者不应修改：
- capabilityEntryPath
- wrapCapabilityPage
- 其他路由组装逻辑
```

这导致开发者无法快速判断：

```text
新增页面应该改哪里？
新增 Provider 应该改哪里？
哪些代码允许修改？
哪些代码属于框架内部实现？
```

本次改造目标是：

> 将 Capability 可修改的 Extension Surface 与 Base 内部实现进行文件级隔离，使开发者只看到少量明确的 Capability 扩展入口，而不需要理解框架内部运行逻辑。

本次不修改：

```text
StrategyUpdatePackageV2
Strategy Registry 基本模型
TEXT_ANCHOR_INSERT 语义
ENSURE_IMPORT 语义
TemplateState
Template Service / Runtime
```

---

# 2. 目标目录结构

建议新增：

```text
frontend/src/capability-extensions/
├── routes.tsx
├── providers.tsx
└── menus.ts
```

该目录定义为：

> Capability Authoring 的前端公共扩展面。

开发者只需要根据能力类型修改这三个文件。

原：

```text
frontend/src/generated/
```

不再作为开发者直接修改入口。

---

# 3. Routes Surface

## 3.1 新增文件

```text
frontend/src/capability-extensions/routes.tsx
```

只保留真正允许 Capability 修改的内容：

```tsx
import type { ReactNode } from 'react';
import type { RouteObject } from 'react-router-dom';
import type { PageRouteDefinition } from '@/typings/routes';

/**
 * 普通业务页面扩展。
 *
 * 大多数 Capability 新增页面时只需要修改这里。
 */
export const capabilityPageRoutes: PageRouteDefinition[] = [
  // xcodeagent:capability-page-routes
];

/**
 * 独立顶层路由扩展。
 *
 * 仅用于 Login / Logout / Callback 等
 * 不使用主 Layout 的页面。
 */
export const capabilityRootRoutes: RouteObject[] = [
  // xcodeagent:capability-root-routes
];

/**
 * 页面横切包装扩展。
 *
 * 用于登录、权限、Feature Flag 等页面统一包装。
 * 普通业务 Capability 通常不需要修改。
 */
export const capabilityPageWrappers:
  Array<(element: ReactNode, page: PageRouteDefinition) => ReactNode> = [
    // xcodeagent:capability-page-wrappers
  ];
```

这里只允许：

```text
ENSURE_IMPORT
TEXT_ANCHOR_INSERT
```

---

# 4. 路由内部逻辑迁移

## 4.1 新增内部文件

建议新增：

```text
frontend/src/routes/capabilityRuntime.tsx
```

将当前开发者不应该修改的逻辑迁移进去：

```text
capabilityEntryPath
wrapCapabilityPage
```

例如：

```tsx
import type { ReactNode } from 'react';

import {
  capabilityPageWrappers,
  capabilityRootRoutes,
} from '@/capability-extensions/routes';

export const capabilityEntryPath =
  capabilityRootRoutes.find(
    route => route.handle?.capabilityEntry,
  )?.path;

export const wrapCapabilityPage = (
  element: ReactNode,
  page: PageRouteDefinition,
): ReactNode =>
  capabilityPageWrappers.reduceRight(
    (current, wrap) => wrap(current, page),
    element,
  );
```

该文件属于：

```text
Base Internal
```

不得注册为 Capability Extension Surface。

---

# 5. 主路由调整

修改：

```text
frontend/src/routes/index.tsx
```

原来从：

```text
@/generated/capabilityRoutes
```

同时获取所有能力。

改成分别读取：

```text
@/capability-extensions/routes
@/routes/capabilityRuntime
```

关系调整为：

```text
capability-extensions/routes.tsx
        │
        ├── capabilityPageRoutes
        ├── capabilityRootRoutes
        └── capabilityPageWrappers
                 │
                 ↓
        capabilityRuntime.tsx
                 │
        ┌────────┴────────┐
        ↓                 ↓
capabilityEntryPath  wrapCapabilityPage
        │                 │
        └────────┬────────┘
                 ↓
          routes/index.tsx
```

保证现有路由行为不变化。

---

# 6. Providers Surface

原：

```text
frontend/src/generated/capabilityProviders.tsx
```

迁移为：

```text
frontend/src/capability-extensions/providers.tsx
```

只保留：

```text
Capability Provider 注册列表
+
xcodeagent:capability-providers Anchor
```

例如：

```tsx
import type { ComponentType, PropsWithChildren } from 'react';

/**
 * Capability 全局 Provider 扩展。
 *
 * 仅当能力需要应用级 Context / Provider 时修改。
 */
export const capabilityProviders:
  ComponentType<PropsWithChildren>[] = [
    // xcodeagent:capability-providers
  ];
```

Provider 的：

```text
reduceRight
Provider Tree 创建
React Root 组装
```

继续放在 Base 内部代码中。

---

# 7. Menus Surface

原：

```text
frontend/src/generated/capabilityMenus.ts
```

调整为：

```text
frontend/src/capability-extensions/menus.ts
```

只暴露 Capability 可插入的位置。

例如：

```tsx
import type { Route } from '@/typings/workbench';

/**
 * Capability 菜单转换扩展。
 *
 * 普通新增页面通常不需要修改。
 * 仅用于 Authorization 等需要统一改变菜单行为的能力。
 */
export const useCapabilityMenus = (
  menus: Route[],
): Route[] => {
  let current = menus;

  // xcodeagent:capability-menu-transforms

  return current;
};
```

暂时不需要进一步拆分内部实现。

---

# 8. Strategy Registry 调整

修改：

```text
template-source/strategy-registry-v2.yaml
```

Target path 从：

```text
frontend/src/generated/capabilityRoutes.tsx
frontend/src/generated/capabilityProviders.tsx
frontend/src/generated/capabilityMenus.ts
```

调整为：

```text
frontend/src/capability-extensions/routes.tsx
frontend/src/capability-extensions/providers.tsx
frontend/src/capability-extensions/menus.ts
```

Target ID 建议保持不变：

```text
frontend.capability-routes
frontend.capability-providers
frontend.capability-menus
```

这样避免不必要地改变：

```text
strategyId
managedMarker
Addition identity
```

Anchor Key 也保持：

```text
capability-root-routes
capability-page-routes
capability-page-wrappers
capability-providers
capability-menu-transforms
```

只调整目标物理路径。

---

# 9. Login / Authorization Capability 迁移

现有：

```text
login
authorization
```

引用的 strategyId 不变。

只需要确保对应 Strategy 的：

```text
targetId
anchorKey
managedMarker
```

保持原值。

由于 Target ID 不变，因此绝大多数 Registry Strategy 无需修改。

Compiler 最终仍生成：

```text
ENSURE_IMPORT
+
TEXT_ANCHOR_INSERT
```

只是作用文件从：

```text
generated/**
```

变成：

```text
capability-extensions/**
```

---

# 10. Base Source 调整

需要同步调整：

```text
template-source/base/base.yaml
```

移除：

```text
frontend/src/generated/capabilityRoutes.tsx
frontend/src/generated/capabilityProviders.tsx
frontend/src/generated/capabilityMenus.ts
```

增加：

```text
frontend/src/capability-extensions/routes.tsx
frontend/src/capability-extensions/providers.tsx
frontend/src/capability-extensions/menus.ts
```

以及新增内部实现文件：

```text
frontend/src/routes/capabilityRuntime.tsx
```

注意：

```text
capabilityRuntime.tsx
```

属于 Base 普通文件，不登记为 Extension Surface。

---

# 11. Capability Analyzer 约束

改造后 Analyzer 的职责更清晰：

```text
capability-extensions/**
→ 允许按照 Registry Surface 规则分析

routes/capabilityRuntime.tsx
→ MODIFY_NON_SURFACE

routes/index.tsx
→ MODIFY_NON_SURFACE
```

因此开发者如果修改内部实现：

```text
frontend/src/routes/capabilityRuntime.tsx
```

执行：

```bash
./capability status xxx
```

应得到：

```text
Unsupported:
  frontend/src/routes/capabilityRuntime.tsx
  Reason: MODIFY_NON_SURFACE
```

形成明确边界。

---

# 12. Workbench 开发体验

改造后，开发者新增一个普通页面：

```text
frontend/src/pages/Page1/index.tsx
```

只需要知道：

```text
新增页面
→ pages/**

注册页面
→ capability-extensions/routes.tsx
→ capability-page-routes
```

例如：

```tsx
export const capabilityPageRoutes = [
  {
    pageId: 'page1',
    name: 'Page 1',
  },
  // xcodeagent:capability-page-routes
];
```

开发者不再需要看到或理解：

```text
capabilityEntryPath
wrapCapabilityPage
routeList
createPageRoutes
Provider Tree 组装
```

---

# 13. 文件内开发提示

三个 Extension Surface 文件顶部统一增加明确说明：

```text
CAPABILITY EXTENSION SURFACE

此文件允许 Capability Authoring 修改。

允许：
- 新增 import
- 在 xcodeagent Anchor 前新增内容

禁止：
- 删除或修改 Anchor
- 修改 Anchor 之外的 Base 逻辑
```

并对每个 Anchor 写明典型场景。

这样开发者进入 Workbench 后无需先阅读框架文档。

---

# 14. 实施步骤

## Stage 1：拆分 Routes Surface

修改：

```text
新增：
frontend/src/capability-extensions/routes.tsx
frontend/src/routes/capabilityRuntime.tsx

修改：
frontend/src/routes/index.tsx
base.yaml
strategy-registry-v2.yaml
```

验收：

```text
login 路由正常
authorization 页面正常
普通 PAGE_ROUTES 正常
根路径跳转行为不变
```

---

## Stage 2：迁移 Providers / Menus

新增：

```text
frontend/src/capability-extensions/providers.tsx
frontend/src/capability-extensions/menus.ts
```

替换旧：

```text
frontend/src/generated/**
```

调整对应 Base import。

验收：

```text
Login Provider 正常
Authorization Provider 正常
Authorization Menu Transform 正常
```

---

## Stage 3：Authoring / Compiler 回归

验证：

```bash
./capability init demo
```

Workbench 中应直接出现：

```text
frontend/src/capability-extensions/
├── routes.tsx
├── providers.tsx
└── menus.ts
```

新增页面并修改：

```text
routes.tsx
```

执行：

```bash
./capability status demo
```

要求：

```text
Anchor inserts = 1
Unsupported = 0
```

再执行：

```bash
./capability build demo
```

要求 Round-trip Verify 通过。

---

## Stage 4：删除旧 Extension Surface

确认所有引用迁移完成后，删除：

```text
frontend/src/generated/capabilityRoutes.tsx
frontend/src/generated/capabilityProviders.tsx
frontend/src/generated/capabilityMenus.ts
```

若 `generated` 目录已无其他职责，则整个目录删除。

禁止长期同时维护两套 Surface。

---

# 15. 自动化测试

至少补充以下场景：

```text
1. capabilityPageRoutes 插入可正常生成 Strategy
2. capabilityRootRoutes 插入可正常生成 Strategy
3. capabilityPageWrappers 插入可正常生成 Strategy
4. capabilityProviders 插入可正常生成 Strategy
5. capabilityMenus 插入可正常生成 Strategy
6. 修改 capabilityRuntime.tsx → MODIFY_NON_SURFACE
7. 删除 Anchor → fail-closed
8. login Generate 结果与迁移前一致
9. authorization Generate 结果与迁移前一致
10. Round-trip Verify 通过
```

最后执行：

```bash
mvn -f template-engine/pom.xml \
  -pl capability-authoring -am test

mvn -f template-engine/pom.xml verify
./scripts/ci/verify-base-frontend.sh

git diff --check
```

---

# 16. 完成标准

改造完成后必须达到：

```text
开发者看到文件名
→ 就能判断应该在哪里扩展

开发者打开文件
→ 就能看到允许修改的 Anchor 和使用场景

框架内部代码
→ 不再与 Extension Surface 混在同一文件

Capability Analyzer
→ 只允许修改显式注册的 Surface

Runtime Contract
→ 不发生变化
```

最终开发者认知应收敛为：

```text
普通页面
→ capability-extensions/routes.tsx

全局 Provider
→ capability-extensions/providers.tsx

菜单增强
→ capability-extensions/menus.ts

其他 Base 文件
→ 默认不要修改
```
