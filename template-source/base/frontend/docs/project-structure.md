# 项目结构与代码放置规则

本文档以当前仓库为准，说明模块职责、依赖边界和平台维护区域。新增代码应先按职责归类，再通过已有入口完成组装。

## 目录与职责

```text
src/
├── apis/          接口请求与响应处理
├── assets/        经构建处理的图片、SVG 等资源
├── components/    可复用 UI 组件
├── constants/     常量及业务路由、菜单配置
├── hooks/         可复用 React Hook
├── layout/        应用公共布局及布局专用组件
├── pages/         路由级业务页面
├── providers/     全局 Context 提供者
├── routes/        React Router 路由树装配
├── styles/        全局主题、变量和基础样式
├── typings/       跨模块 TypeScript 类型
└── utils/         无业务状态的转换、路由和通用工具

public/            不经打包处理、按原路径提供的静态文件
scripts/           构建和开发辅助脚本
devtools/          开发调试工具
tests/             Jest 测试、测试配置和 mock
```

## 代码放置规则

- 路由级业务页面放在 `src/pages/<Feature>/index.tsx`；页面私有组件、状态和样式可与页面同目录。
- 跨页面复用的 UI 放在 `src/components/`；仅服务于布局的组件放在 `src/layout/components/`。
- HTTP 调用及请求、响应处理放在 `src/apis/`；页面和组件不得直接重复实现 Axios 调用。
- 页面地址、菜单元数据和页面标识统一声明在 `src/constants/routes.tsx` 的 `PAGE_ROUTES` 中。业务页必须使用小写 snake_case 的 `pageId`：`pageId: 'asset_list'` 确定性对应 `src/pages/AssetList/index.tsx` 与 `/page/asset-list`。平台系统页可用显式 `modulePath`，例如 `System/Role` 对应 `src/pages/System/Role/index.tsx`。
- 公共类型放在 `src/typings/`，可复用 Hook 放在 `src/hooks/`；不依赖 UI 的转换逻辑放在 `src/utils/`。
- 全局样式放在 `src/styles/`，页面和组件局部样式应就近放置。构建资源放在 `src/assets/`，原样静态资源放在 `public/`。

## 路由、菜单与依赖边界

业务导航使用树形 `PageRouteDefinition`；根数组中的页面节点天然兼容扁平菜单。`routes/`、`layout/` 均从 `PAGE_ROUTES` 派生数据，不得重新维护独立菜单或业务路由列表。

```text
PAGE_ROUTES
 ├─ utils/pageRoutes.tsx  → 懒加载页面并生成 React Router 路由
 ├─ utils/route.tsx       → 生成 ProLayout 菜单和 /page 入口路径
 ├─ routes/index.tsx      → 将业务页挂载到 /page/...
 └─ layout/index.tsx      → 渲染菜单
```

- 上层可以组合下层；`utils`、`typings`、`constants` 不得反向依赖页面、布局、路由树或应用入口。
- 业务页面 URL 始终以 `/page/...` 开头。无路径目录仅用于菜单分组，不生成独立路由。
- 页面通过 `pageId` 自动发现和懒加载；不得为业务页面新增静态 import 插槽或在路由树中手工注册组件。业务页地址只由 `pageId` 推导，不受菜单目录层级影响。

## 平台托管与禁止直接修改区域

| 区域 | 规则 | 正确修改方式 |
| --- | --- | --- |
| `src/constants/routes.tsx` 中的 `XCODEAGENT_BUSINESS_ROUTES_START/END` 标记 | 业务页面、目录和外链仅能在该标记之间添加；保留标记及其外部的路由根定义。 | 先创建页面文件，再在标记区内添加包含 `name` 和 `pageId` 的业务页面节点；目录只使用 `children`，外链使用 `path` 与 `isUrl`。 |
| `src/routes/index.tsx`、`src/layout/index.tsx`、`src/utils/pageRoutes.tsx`、`src/utils/route.tsx` | 这些文件共同实现统一路由与菜单派生；不得通过恢复 `BIZ_MENUS`、独立菜单配置或手工业务路由注册绕过它们。 | 仅在修改统一路由机制本身时调整，并同时补充测试。 |

未被上述规则明确标识的文件不因属于模板骨架而自动视为禁止修改。

## 新增业务页面清单

1. 选择小写 snake_case 的 `pageId`，并创建 `src/pages/<PascalCase(pageId)>/index.tsx`。
2. 在业务标记区添加 `PAGE_ROUTES` 节点；扁平页面直接放在根数组，目录使用 `children`。
3. 为页面填写菜单名称与 `pageId`；系统页才填写显式 `path`、`modulePath`。外链使用既有 `path`、`isUrl`、`target` 字段。
4. 运行路由测试并确认页面地址为预期的 `/page/...`。
