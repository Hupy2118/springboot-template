import type { PageRouteDefinition } from '@/typings/routes';
import Welcome from '@/pages/Welcome';
// XCODEAGENT_BUSINESS_ROUTE_IMPORTS_START
// XCODEAGENT_BUSINESS_ROUTE_IMPORTS_END

export const PAGE_ROUTE = 'page';
export const PAGE_ROUTES: PageRouteDefinition[] = [
  { name: '欢迎页', pageId: 'welcome', component: Welcome },
  // XCODEAGENT_BUSINESS_ROUTES_START
  // XCODEAGENT_BUSINESS_ROUTES_END
];
