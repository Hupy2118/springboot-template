import { Navigate, useRoutes, type RouteObject } from 'react-router-dom';
import Layout from '@/layout';
import { PAGE_ROUTE } from '@/constants/routes';
import { applyAppRouteGuards, createPageRoutes } from '@/routes/routeBuilder';
import { appPageRoutes } from '@/routes/pageRegistry';
import { findFirstPagePath } from '@/utils/route';
import { capabilityRootRoutes } from '@/capability-extensions/routes';

const first = findFirstPagePath(appPageRoutes, PAGE_ROUTE);
const businessRoute: RouteObject = {
  path: PAGE_ROUTE,
  element: <Layout />,
  children: [
    { index: true, element: first ? <Navigate to={first} replace /> : <div>暂无页面</div> },
    ...createPageRoutes(appPageRoutes),
  ],
};
const routeList: RouteObject[] = [{ path: '/', children: [
  ...capabilityRootRoutes,
  applyAppRouteGuards(businessRoute),
  { index: true, element: <Navigate to={PAGE_ROUTE} replace /> },
] }];
const Routes = () => useRoutes(routeList);
export { Routes, routeList };
