import type { RouteObject } from 'react-router-dom';
import { RouteGuard } from '@/components/Authorization/RouteGuard';
import { createPageRoutes } from '@/utils/pageRoutes';
import type { ResolvedPageRoute } from '@/utils/pageRouteTree';

/** auth 专有权限层：在共享页面路由上包裹资源守卫。 */
export function createProtectedRoutes(
  items: ResolvedPageRoute[],
): RouteObject[] {
  return createPageRoutes(items, (element, item) =>
    item.requiredResourceKeys.length ? (
      <RouteGuard resourceKeys={item.requiredResourceKeys}>
        {element}
      </RouteGuard>
    ) : (
      element
    ),
  );
}
