import { RequireLogin } from '@/components/RequireLogin';
import type { RouteGuardDefinition } from '@/capability-extensions/routeGuards';

export function createLoginRouteGuard(): RouteGuardDefinition {
  return { element: <RequireLogin /> };
}
