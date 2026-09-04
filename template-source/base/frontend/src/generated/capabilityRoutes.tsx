import type { ReactNode } from 'react';
import type { RouteObject } from 'react-router-dom';
import type { PageRouteDefinition } from '@/typings/routes';

export const capabilityRootRoutes: RouteObject[] = [];
export const capabilityEntryPath: string | undefined = undefined;
export const capabilityPageRoutes: PageRouteDefinition[] = [];
export const wrapCapabilityPage = (
  element: ReactNode,
  _page: PageRouteDefinition,
): ReactNode => element;
