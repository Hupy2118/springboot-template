import { PAGE_ROUTES } from '@/constants/routes';
import { capabilityPageRoutes } from '@/capability-extensions/routes';
import type { PageRouteDefinition } from '@/typings/routes';

/** Application pages have exactly one aggregation point. */
export const appPageRoutes: PageRouteDefinition[] = [
  ...PAGE_ROUTES,
  ...capabilityPageRoutes,
];
