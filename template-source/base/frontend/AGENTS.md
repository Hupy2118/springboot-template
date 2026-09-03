# Agent Development Guide

## Project Context

修改代码前必须参考：

- `docs/project-structure.md`：工程结构、页面注册和代码放置约定。
- `.xcodeagent/context/codebase-manifest.json`：当前工程有效架构视图；仅当该文件存在时必须参考。

## Code Change Rules

- 新增代码遵循 `docs/project-structure.md` 中的代码放置规则。
- 业务页面、目录和外链只能在 `src/constants/routes.tsx` 的 `XCODEAGENT_BUSINESS_ROUTES_START/END` 标记之间注册。
- 新增业务页面必须使用小写 snake_case 的 `pageId` 自动发现和懒加载：`asset_list` 对应 `src/pages/AssetList/index.tsx` 和 `/page/asset-list`。不得恢复独立 `BIZ_MENUS`、业务页面静态 import 插槽或手工业务路由注册。
- 不得修改 `docs/project-structure.md` 所列的平台维护区域，除非任务明确授权。
- 优先沿用当前工程已有结构，不得自行创建新的架构层。

## Structure Maintenance

如果本次修改改变了工程级目录、模块职责、路由/菜单数据流、层级依赖或平台维护边界，应同步更新 `docs/project-structure.md`。

普通业务文件、页面、组件、Hook、API 模块的新增或修改不需要更新该文档，除非其改变了上述约定。
