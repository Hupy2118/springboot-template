import type { RouteObject } from 'react-router-dom';
import { RouteGuard } from '@/components/authorization/RouteGuard';
import type { PageRouteDefinition } from '@/typings/routes';
import { createPageRoutes } from '@/utils/pageRoutes';

/** auth 专有权限层：在共享页面路由上包裹资源守卫。 */
export function createProtectedRoutes(items: PageRouteDefinition[]): RouteObject[] {
  return createPageRoutes(items, (element, item) => item.resourceKey
    ? <RouteGuard resourceKey={item.resourceKey}>{element}</RouteGuard>
    : element);
}
