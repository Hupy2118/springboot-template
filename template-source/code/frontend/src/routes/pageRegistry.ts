import { PAGE_ROUTE, PAGE_ROUTES } from '@/constants/routes';
import { resolvePageRouteTree } from '@/utils/pageRouteTree';

export const APP_PAGE_ROUTES = PAGE_ROUTES;
export const APP_PAGE_ROUTE_TREE = resolvePageRouteTree(APP_PAGE_ROUTES, PAGE_ROUTE);
