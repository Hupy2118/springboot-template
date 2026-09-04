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

- 页面、目录和外链只在 `src/constants/routes.tsx` 的 `XCODEAGENT_BUSINESS_ROUTES_START/END` 标记之间注册。
- 业务页面必须使用小写 snake_case `pageId`。例如 `asset_list` 对应 `src/pages/AssetList/index.tsx` 和 `/page/asset-list`。
- 基础业务页面由既有页面发现机制自动懒加载；能力页面由引擎生成的 `src/generated/capabilityRoutes.tsx` 以显式组件引用接入。不得绕开该生成入口手写第二份能力路由或菜单配置。
- 菜单始终先由 `createLayoutMenus` 从基础路由与 `capabilityPageRoutes` 汇总，再交给引擎生成的 `useCapabilityMenus` 变换链；页面守卫同样只通过 `capabilityRoutes.tsx` 的生成包装链接入。

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
