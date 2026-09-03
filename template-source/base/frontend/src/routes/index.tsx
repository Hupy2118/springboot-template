import { Navigate, useRoutes, type RouteObject } from 'react-router-dom';
import Layout from '@/layout';
import { PAGE_ROUTE, PAGE_ROUTES } from '@/constants/routes';
import { createPageRoutes } from '@/utils/pageRoutes';
import { findFirstPagePath } from '@/utils/route';
import { capabilityPageRoutes, capabilityRootRoutes, wrapCapabilityPage } from '@/generated/capabilityRoutes';

const pages = [...PAGE_ROUTES, ...capabilityPageRoutes];
const first = findFirstPagePath(pages, PAGE_ROUTE);
const routeList: RouteObject[] = [{ path: '/', children: [
  { path: PAGE_ROUTE, element: <Layout />, children: [{ index: true, element: first ? <Navigate to={first} replace /> : <div>暂无页面</div> }, ...createPageRoutes(pages, wrapCapabilityPage)] },
  ...capabilityRootRoutes,
  { index: true, element: <Navigate to={PAGE_ROUTE} replace /> },
] }];
const Routes = () => useRoutes(routeList);
export { Routes, routeList };
