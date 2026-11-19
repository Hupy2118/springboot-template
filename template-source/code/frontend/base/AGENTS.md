# 前端工程架构与能力说明

## 1. 工程概览与技术栈

本文件说明当前模板的工程结构、已有入口及文件归属。通用前端编码规范由 XCodeAgent 的 `frontend-engineering-standards` Skill 维护，不在此重复定义。

工程使用 React 18、TypeScript、React Router 6、Ant Design 5、ahooks、Axios 和 Rsbuild；实际依赖、脚本与 Node engines 以 `package.json` 为准。当前 Node 范围为 `>=20.19 <23`，包管理器为 pnpm。工程开发/维护命令：`pnpm install`、`pnpm dev`、`pnpm build`、`pnpm test`；路由契约检查为 `pnpm test:route-projector`。这些命令供开发参考，不构成对代码生成 Agent 的 Shell 执行授权。

## 2. 工程目录与文件职责

以下为 Base 的关键文件；能力新增文件在对应能力章节中说明。

```text
frontend/
├── AGENTS.md                         当前生成工程的架构与能力说明
├── package.json                      依赖、脚本、Node engines
├── pnpm-lock.yaml                    依赖锁
├── rsbuild.config.ts                 构建与开发服务配置
└── src/
    ├── index.tsx                     主题应用、扩展初始化、React 挂载入口
    ├── App.tsx                       路由、主题、错误边界及 Provider 组合
    ├── apis/service.ts               统一 HTTP 客户端（默认导出）
    ├── apis/welcome.ts               Base 示例 API
    ├── pages/Home/index.tsx          Base 首页
    ├── components/AppStateView/      状态展示组件
    ├── components/ErrorBoundary/     全局错误边界
    ├── hooks/usePageMenus.ts         当前访问状态下的菜单与首个可访问路径
    ├── config/appConfig.ts           应用名称、图标、主题配置
    ├── config/applyTheme.ts          挂载前应用主题 CSS 变量
    ├── constants/index.ts            公共常量
    ├── constants/layout.ts           布局常量
    ├── constants/routes.tsx          PAGE_ROUTE、PAGE_ROUTES
    ├── typings/                      config、routes、workbench 等共享类型
    ├── routes/index.tsx              React Router 路由入口
    ├── routes/pageRegistry.ts        业务与系统页面路由的共享静态树
    ├── routes/routeBuilder.tsx       页面路由与 AccessBoundary 组合
    ├── routes/rootRoutes.tsx         扩展根路由入口
    ├── routes/systemPageRoutes.ts    扩展系统页面入口
    ├── platform/identity/            IdentityContext、IdentityProvider、useIdentity
    ├── platform/access/              AccessContext、AccessBoundary、useAccess、AccessStateView
    ├── providers/AppProviders.tsx    身份 Provider 与扩展 Provider 组合
    ├── layout/index.tsx              ProLayout 框架布局
    ├── bootstrap/appInitializers.ts  扩展初始化入口
    ├── observability/errorReporter.ts 扩展错误上报入口
    └── utils/
        ├── pageIdentity.ts           页面标识到目录名、URL 段的映射
        ├── pageRouteTree.ts          页面配置标准化为共享静态树
        └── pageRoutes.tsx            页面模块动态发现、懒加载与路由生成
```

`src/extensions/` 的五个 Registry 属于组装生成结果，不是 Base 源码树自带的普通文件，见第 4 节。开发预览的 Element Inspector 入口位于 `devtools/elementInspector.ts`，由 `src/index.tsx` 在开发模式下按需加载。

## 3. 基础能力及接入入口

### HTTP service 与应用配置

`src/apis/service.ts` 默认导出 `service`，导入方式为 `import service from '@/apis/service'`。当前客户端启用请求凭据，处理 Authorization 请求头/响应头与错误；统一响应的 `returnCode === 'SUC0000'` 时返回 `body`，其他业务返回码抛出 `ServiceResponseError`。业务模块收到的已经是 body，不要二次拆包。

`src/config/appConfig.ts` 从构建时环境变量 `PUBLIC_APP_NAME`、`PUBLIC_APP_ICON`、`PUBLIC_THEME_PRIMARY_COLOR` 读取应用配置，并提供默认值。正式 Generate 将应用参数投影到前端 `.env`；`src/config/applyTheme.ts` 在 React 挂载前设置主题变量，`src/App.tsx` 向 Ant Design 注入主题。

### 页面注册、路由与菜单

业务路由根由 `PAGE_ROUTE = 'page'` 定义，Base 首页为 `/page/home`。页面与菜单共享以下数据流：

```text
src/constants/routes.tsx (PAGE_ROUTES)
  + src/routes/systemPageRoutes.ts (扩展系统页面)
    → src/routes/pageRegistry.ts (合并页面配置)
      → src/utils/pageRouteTree.ts (标准化为 APP_PAGE_ROUTE_TREE)
        → src/routes/routeBuilder.tsx (AccessBoundary + 页面路由)
        → src/hooks/usePageMenus.ts (可访问菜单)
```

`src/utils/pageIdentity.ts` 验证小写 snake_case 页面标识，将其映射为 PascalCase 目录名和短横线 URL 段。业务页面位于 `src/pages/<PascalCase>/index.tsx`，默认导出 React 组件。`src/utils/pageRoutes.tsx` 使用 Rsbuild/Rspack 的 `import.meta.webpackContext` 动态发现页面并懒加载，无需逐页增加静态 import。根路由与系统页面通过各自的扩展入口接入，不维护第二份业务菜单事实。

`src/constants/routes.tsx` 的 `DEVAGENTSTUDIO_BUSINESS_ROUTES_START/END` 区域由 Template Route Projector / 平台投影，普通页面任务不手工登记。

### 身份与访问基础设施

`src/providers/AppProviders.tsx` 用 `IdentityProvider` 包裹按 Registry 顺序组合的扩展 Provider。`src/platform/identity/IdentityContext.tsx` 保存身份，`src/platform/identity/useIdentity.ts` 提供读取与更新入口。

`src/platform/access/AccessContext.tsx` 定义访问状态及资源检查接口，Base 默认状态为 ready、资源检查返回 true；启用的能力可提供实际访问状态。`src/platform/access/AccessBoundary.tsx` 在页面声明资源键时执行状态与资源检查；没有资源键时直接呈现页面。路由和菜单共同消费这些基础设施。

## 4. 工程文件所有权与受管边界

| 位置 | 归属与职责 |
| --- | --- |
| `src/pages/<Feature>/`、`src/apis/<Business>.ts` | 业务页面与 API 模块位置；API 复用 service，具体可写文件以任务授权为准 |
| `src/components/`、`src/hooks/`、`src/constants/`、`src/typings/` | 可承载已授权业务扩展；目录中的框架/能力文件不因此自动可修改 |
| `src/App.tsx`、`src/index.tsx`、`src/routes/`、`src/layout/`、`src/platform/`、`src/providers/` | 模板基础设施；普通业务任务不自行改写 |
| `src/apis/service.ts`、`src/config/` | 模板统一请求与应用配置入口，业务代码复用 |
| `src/constants/routes.tsx` 的业务标记区 | 平台 Route Projector 投影内容 |
| `src/extensions/providers.ts`、`src/extensions/rootRoutes.tsx`、`src/extensions/systemPageRoutes.ts`、`src/extensions/initializers.ts`、`src/extensions/errorReporters.ts` | 现有 Assembly/生成器产物，分别装配 Provider、根路由、系统页面、初始化器和错误上报器，业务代码不手工覆盖 |
| `package.json`、`rsbuild.config.ts`、`pnpm-lock.yaml` | 模板依赖与构建配置，普通业务任务不修改 |

本表说明工程所有权，不授予任何文件读写权限；当前任务的精确权限以 XCodeAgent 已批准的任务契约和工具校验为准。

本文件由模板 Base 与有效能力片段组合生成。模板维护者调整工程结构或能力时更新对应源码文档来源；普通业务 Build 不编辑 AGENTS.md，也不将组合后的能力章节回写 Base。
