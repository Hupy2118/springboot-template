<!-- DEVAGENTSTUDIO:CAPABILITY:authorization:BEGIN -->
## 权限与访问控制

`src/providers/AuthorizationProvider.tsx` 的 `AuthorizationProvider` 调用 `src/apis/authorization.ts` 的 `getMyResources` 获取资源键，经 `src/extensions/providers.ts` 注册入口接入应用，向 `src/platform/access/AccessContext.tsx` 提供状态及资源检查：加载中为 loading，401 或 XCD1B11 为 unauthenticated，503 或 XCD1B12 为 not-ready，其他请求错误为 error；成功时为 ready，并按返回的资源键集合检查权限。`src/platform/access/AccessBoundary.tsx` 在页面路由组装中处理声明资源键的页面访问；普通页面复用该入口，不另建 RouteGuard 或复制 AccessContext。

组件导入为 `import { Permission } from '@/components/Authorization/Permission'`，接收 `resourceKey` 与单个 `ReactElement` child，默认 `mode='hidden'`，也支持 `mode='disabled'`。逻辑判断导入为 `import { usePermission } from '@/hooks/usePermission'`，提供 `hasPermission`、`hasAnyPermission`、`hasAllPermissions`；该 Hook 在访问状态未 ready 时返回不通过。

资源常量来自 `import { RESOURCES } from '@/constants/resources'`。内置 `RESOURCES.SYSTEM.AUTHORIZATION_MANAGEMENT` 对应 `system_authorization_management`，仅代表系统权限管理资源，不是通用业务按钮资源键。业务资源常量以后续平台确定性投影与任务合同为准，不将该系统资源套用于普通业务操作。

`src/pages/AuthorizationManagement/index.tsx` 通过系统页面 Registry 接入，路由标识为 `authorization_management`；不将它作为普通业务页面重复注册。业务页面由 Route Projector 投影。权限领域请求及类型分别位于 `src/apis/authorization.ts`、`src/typings/authorization.ts`，沿用已有客户端与资源上下文。
<!-- DEVAGENTSTUDIO:CAPABILITY:authorization:END -->
