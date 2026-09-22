# 项目结构与代码放置规则

本文档描述生成后的应用工程目录，供业务代码生成与维护使用。新增代码应先按职责归类，再通过既有入口完成组装。

## 目录职责

```text
src/
├── apis/        接口请求与响应解包
├── assets/      构建期静态资源
├── components/  可复用展示组件
├── constants/   页面和应用常量
├── hooks/       可复用 React Hook
├── layout/      应用公共布局及其专用组件
├── pages/       路由级业务页面
├── styles/      全局主题、变量和基础样式
├── typings/     跨模块类型
└── utils/       无 UI 的转换与辅助工具
```

`public/` 存放原样提供的静态文件；`scripts/` 存放构建或开发辅助脚本。

## 代码放置规则

- 可由路由直接访问的业务界面放在 `src/pages/<Feature>/`；页面特有组件、状态和样式可与页面同目录。
- 被两个及以上页面或布局复用的展示组件放在 `src/components/`；只服务于布局的组件放在 `src/layout/components/`。
- HTTP 调用、请求参数和响应解包放在 `src/apis/`；页面与组件复用既有 `src/apis/service.ts`，不得重复创建请求客户端。
- 跨模块类型放在 `src/typings/`；仅模块内部使用的类型优先就近维护。无 React/UI 依赖的逻辑放在 `src/utils/`，可复用状态逻辑放在 `src/hooks/`。
- 全局样式、主题和 Less 变量放在 `src/styles/`；页面或组件私有样式与其代码同目录。

## 页面与路由

- 共享业务页面路由由 Template Route Projector 统一注册到 `src/constants/routes.tsx` 的 `DEVAGENTSTUDIO_BUSINESS_ROUTES_START/END` 受管区域。Page Agent 只负责生成 `src/pages/<Feature>/` 页面实现，不得直接编辑该区域。
- Route Projector 位于 `scripts/devagentstudio/route-projector.mjs`，只接受 `route-projector.v2`。其 Descriptor、Input Schema 与 Output Schema 分别投影至 `.devagentstudio/template-contracts/route-projector.json`、`route-projector-input.schema.json`、`route-projector-output.schema.json`；调用方只通过这些公开契约调用它。
- Projector 按 `pageId → PascalCase 页面目录 → src/pages/<PageDirectory>/index.tsx` 判断页面是否存在。仅非链接的普通 `index.tsx` 进入 `appliedPageIds`；缺失、目录、链接或其他非普通入口进入 `skippedPageIds`。`welcome` 与 Capability 平台页面等保留 ID 由模板内部拒绝，调用方不维护该集合。
- Projector 每次以当前输入顺序全量 reconcile 受管区域，stdout 只输出 `status`、`requestedPageIds`、`appliedPageIds`、`skippedPageIds`。它先完成全部校验和内存生成，再通过同目录临时文件、`fsync`、rename 替换写入；Windows 遇到短暂的占用错误会有限重试。写入保持原文件的换行符约定，避免不同系统的 checkout 设置产生伪变更。
- 所有进入主 Layout 的页面（业务、系统和 Capability）必须使用小写 snake_case `pageId`。例如 `asset_list` 对应 `src/pages/AssetList/index.tsx` 和 `/page/asset-list`。
- 主 Layout 页面始终由 `pageId` 自动懒加载；Route Projector 只注册 `name`、`pageId` 与可选 `resourceKey`，不得使用 `component`、`path` 或额外 import 绕开该约定。Capability 页面仍由 `src/capability-extensions/routes.tsx` 接入，但与业务页面使用完全相同的页面发现机制；不得绕开该扩展面手写第二份能力路由或菜单配置。
- `PAGE_ROUTES` 与 `capabilityPageRoutes` 仅在 `src/routes/pageRegistry.ts` 聚合为 `appPageRoutes`。菜单由 `createLayoutMenus(appPageRoutes)` 投影，再交给 `src/capability-extensions/menuTransforms.ts` 的 `useCapabilityMenuTransforms` 处理；App / Page Guard 分别通过 `routeGuards.tsx` 的注册面进入 Route Builder。

## 动态 Capability 文档

每个启用的 Capability 会与其代码一同生成 `docs/capabilities/<capability>.md`。该文档说明该能力实际加入的页面、路由、Provider、接口模块和维护边界。Capability 被禁用时，对应文档不会存在；不要手工维护一份脱离当前 Capability State 的功能清单。

## 依赖方向

```text
应用入口 / 路由 / 布局
            ↓
        页面与组件
            ↓
       Hook 与接口模块
            ↓
  类型、常量、工具、样式与资源
```

下层不得反向导入页面、布局、路由树或应用入口。目录职责、路由/菜单数据流或依赖方向发生变化时，必须同步更新本文档。
