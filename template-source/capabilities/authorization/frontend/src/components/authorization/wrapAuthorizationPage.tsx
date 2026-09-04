import type { ReactNode } from 'react';
import { RouteGuard } from '@/components/authorization/RouteGuard';
import type { PageRouteDefinition } from '@/typings/routes';

export function wrapAuthorizationPage(element: ReactNode, route: PageRouteDefinition): ReactNode {
  return route.resourceKey ? <RouteGuard resourceKey={route.resourceKey}>{element}</RouteGuard> : element;
}
