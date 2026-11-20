# 前端工程架构与能力说明

## 工程概览与技术栈

本文介绍当前前端工程的主要目录、已有功能与使用入口；具体操作遵守当前任务授权。

工程使用 React 18、TypeScript、React Router 6、Ant Design 5、ProComponents、ahooks、Axios 和 Rsbuild；实际依赖、脚本与 Node engines 以 `package.json` 为准。当前 Node 范围为 `>=20.19 <23`，包管理器为 pnpm。本地开发命令：`pnpm install`、`pnpm dev`、`pnpm build`、`pnpm test`；路由契约检查为 `pnpm test:route-projector`。这些命令供开发参考，不构成对代码生成 Agent 的 Shell 执行授权。

## 工程目录与文件职责

以下目录概览来自当前工程的实际文件，省略测试、静态资源及部分辅助文件。

<!-- DEVAGENTSTUDIO:FRONTEND_PROJECT_TREE -->

开发预览的 Element Inspector 入口位于 `devtools/elementInspector.ts`，由 `src/index.tsx` 在开发模式下按需加载。

## 核心架构与使用入口

### HTTP service 与应用配置

`src/apis/service.ts` 默认导出 `service`，导入方式为 `import service from '@/apis/service'`。当前客户端启用请求凭据，处理 Authorization 请求头/响应头与错误；统一响应的 `returnCode === 'SUC0000'` 时返回 `body`，其他业务返回码抛出 `ServiceResponseError`。业务模块收到的已经是 body，不要二次拆包。

`src/config/appConfig.ts` 从构建时环境变量 `PUBLIC_APP_NAME`、`PUBLIC_APP_ICON`、`PUBLIC_THEME_PRIMARY_COLOR` 读取应用配置，并提供默认值。应用环境参数位于前端 `.env`；`src/config/applyTheme.ts` 在 React 挂载前设置主题变量，`src/App.tsx` 向 Ant Design 注入主题。

### 页面注册、路由与菜单

业务路由根由 `PAGE_ROUTE = 'page'` 定义，当前首页为 `/page/home`。页面与菜单共享以下数据流：

```text
src/constants/routes.tsx (PAGE_ROUTES)
  + src/routes/systemPageRoutes.ts (扩展系统页面)
    → src/routes/pageRegistry.ts (合并页面配置)
      → src/utils/pageRouteTree.ts (标准化为 APP_PAGE_ROUTE_TREE)
        → src/routes/routeBuilder.tsx (AccessBoundary + 页面路由)
        → src/hooks/usePageMenus.ts (可访问菜单)
```

`src/utils/pageIdentity.ts` 验证小写 snake_case 页面标识，将其映射为 PascalCase 目录名和短横线 URL 段。业务页面位于 `src/pages/<PascalCase>/index.tsx`，默认导出 React 组件。`src/utils/pageRoutes.tsx` 使用 Rsbuild/Rspack 的 `import.meta.webpackContext` 动态发现页面并懒加载，无需逐页增加静态 import。根路由与系统页面通过各自的扩展入口接入，不维护第二份业务菜单事实。

`src/constants/routes.tsx` 的 `DEVAGENTSTUDIO_BUSINESS_ROUTES_START/END` 区域由 Route Projector / 平台投影，普通页面任务不手工登记。

### 身份与访问基础设施

`src/providers/AppProviders.tsx` 用 `IdentityProvider` 包裹按 Registry 顺序组合的扩展 Provider。`src/platform/identity/IdentityContext.tsx` 保存身份，`src/platform/identity/useIdentity.ts` 提供读取与更新入口。

`src/platform/access/AccessContext.tsx` 定义访问状态及资源检查接口，未提供访问上下文时，默认状态为 ready、资源检查返回 true；Provider 提供访问上下文时，状态与资源检查以该上下文为准。`src/platform/access/AccessBoundary.tsx` 在页面声明资源键时执行状态与资源检查；没有资源键时直接呈现页面。路由和菜单共同消费这些基础设施。

## 文件职责与受管边界

| 位置 | 归属与职责 |
| --- | --- |
| `src/pages/<Feature>/`、`src/apis/<Business>.ts` | 业务页面与 API 模块位置；API 复用 service，具体可写文件以任务授权为准 |
| `src/components/`、`src/hooks/`、`src/constants/`、`src/typings/` | 可承载已授权业务扩展；目录中的框架/能力文件不因此自动可修改 |
| `src/App.tsx`、`src/index.tsx`、`src/routes/`、`src/layout/`、`src/platform/`、`src/providers/` | 工程基础设施；普通业务任务不自行改写 |
| `src/apis/service.ts`、`src/config/` | 统一请求与应用配置入口，业务代码复用 |
| `src/constants/routes.tsx` 的业务标记区 | 平台 Route Projector 投影内容 |
| `src/extensions/providers.ts`、`src/extensions/rootRoutes.tsx`、`src/extensions/systemPageRoutes.ts`、`src/extensions/initializers.ts`、`src/extensions/errorReporters.ts` | 受管注册入口，分别连接 Provider、根路由、系统页面、初始化器和错误上报器，业务代码不手工覆盖 |
| `package.json`、`rsbuild.config.ts`、`pnpm-lock.yaml` | 工程依赖与构建配置，普通业务任务不修改 |

本表说明工程所有权，不授予任何文件读写权限；当前任务的精确权限以 XCodeAgent 已批准的任务契约和工具校验为准。
