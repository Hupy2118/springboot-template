import type { ReactNode } from 'react';
import type { RouteObject } from 'react-router-dom';
import type { PageRouteDefinition } from '@/typings/routes';

/*
 * CAPABILITY EXTENSION SURFACE
 *
 * 此文件允许 Capability Authoring 修改。
 * 允许新增 import，以及在 xcodeagent Anchor 前新增内容。
 * 禁止删除或修改 Anchor，或修改 Anchor 之外的 Base 内容。
 */

/** 仅用于 Login、Logout、Callback 等不使用主 Layout 的能力路由。 */
export const capabilityRootRoutes: RouteObject[] = [
  // xcodeagent:capability-root-routes
];

/** 普通业务 Capability 页面在此注册。 */
export const capabilityPageRoutes: PageRouteDefinition[] = [
  // xcodeagent:capability-page-routes
];

/** 用于登录、权限与 Feature Flag 等横切页面包装。 */
export const capabilityPageWrappers: Array<(
  element: ReactNode,
  page: PageRouteDefinition,
) => ReactNode> = [
  // xcodeagent:capability-page-wrappers
];
