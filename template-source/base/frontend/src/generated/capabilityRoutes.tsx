import type { ReactNode } from 'react';
import type { RouteObject } from 'react-router-dom';
import type { PageRouteDefinition } from '@/typings/routes';

export const capabilityRootRoutes: RouteObject[] = [
  // xcodeagent:capability-root-routes
];
export const capabilityEntryPath: string | undefined = undefined;
export const capabilityPageRoutes: PageRouteDefinition[] = [
  // xcodeagent:capability-page-routes
];
const capabilityPageWrappers: Array<(element: ReactNode, page: PageRouteDefinition) => ReactNode> = [
  // xcodeagent:capability-page-wrappers
];
export const wrapCapabilityPage = (element: ReactNode, page: PageRouteDefinition): ReactNode =>
  capabilityPageWrappers.reduceRight((current, wrap) => wrap(current, page), element);
