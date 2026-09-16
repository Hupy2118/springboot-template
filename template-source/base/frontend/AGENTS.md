# Agent Development Guide

## Project Context

修改代码前必须参考：

- `docs/project-structure.md`：工程结构、页面注册和代码放置约定。
- `.xcodeagent/context/codebase-manifest.json`：当前工程有效架构视图；仅当该文件存在时必须参考。

## Code Change Rules

- 新增代码遵循 `docs/project-structure.md` 的目录职责和依赖方向。
- 启用 Capability 后，参考随其代码生成的 `docs/capabilities/<capability>.md`；不要在未启用 Capability 的工程中预先创建或引用该文档。
- 业务页面、目录和外链只能在 `src/constants/routes.tsx` 的 `XCODEAGENT_BUSINESS_ROUTES_START/END` 标记之间注册；使用显式组件时，对应 import 只能添加在 `XCODEAGENT_BUSINESS_ROUTE_IMPORTS_START/END` 标记之间。
- 新增业务页面必须使用小写 snake_case `pageId`：它映射到 PascalCase 页面目录和短横线路由。标准页面可依赖页面自动发现；若路由声明使用 `component`，必须同时在业务路由 import 区注册该组件。不得手工业务路由或维护第二份菜单配置。
- 优先沿用现有工程结构；除非任务明确要求，不得自行新增架构层或改变基础路由/布局机制。

## Structure Maintenance

当修改改变目录职责、路由或菜单数据流、层级依赖或页面注册约定时，同步更新 `docs/project-structure.md`。普通业务文件、页面、组件、Hook、API 模块的新增或修改不需要更新该文档。
