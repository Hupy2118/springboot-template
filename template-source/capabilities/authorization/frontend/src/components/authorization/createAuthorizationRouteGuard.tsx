import { AuthorizationGuard } from '@/components/authorization/RouteGuard';
import type { RouteGuardDefinition } from '@/capability-extensions/routeGuards';
import type { PageRouteDefinition } from '@/typings/routes';

export function createAuthorizationRouteGuard(
  page: PageRouteDefinition,
): RouteGuardDefinition | undefined {
  return page.resourceKey
    ? { element: <AuthorizationGuard resourceKey={page.resourceKey} /> }
    : undefined;
}
