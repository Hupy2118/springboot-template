import type { RouteObject } from 'react-router-dom';
import { AccessBoundary } from '@/platform/access/AccessBoundary';
import { createPageRoutes } from '@/utils/pageRoutes';
import type { ResolvedPageRoute } from '@/utils/pageRouteTree';

export function createAccessibleRoutes(items: ResolvedPageRoute[]): RouteObject[] {
  return createPageRoutes(items, (element, item) => (
    <AccessBoundary resourceKeys={item.requiredResourceKeys}>{element}</AccessBoundary>
  ));
}
