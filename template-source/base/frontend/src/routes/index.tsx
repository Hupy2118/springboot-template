import { RouteObject, useRoutes, Navigate } from 'react-router-dom';
import { PAGE_ROUTE, PAGE_ROUTES } from '@/constants/routes';
import Layout from '@/layout';
import Login from '@/pages/Login';
import Logout from '@/pages/Logout';
import { findFirstPagePath } from '@/utils/route';
import { createPageRoutes } from '@/utils/pageRoutes';

const firstPagePath = findFirstPagePath(PAGE_ROUTES, PAGE_ROUTE);

/**
 * 注册页面路由
 */
const routeList: RouteObject[] = [
  {
    path: '/',
    children: [
      {
        path: PAGE_ROUTE,
        element: <Layout />,
        children: [
          {
            index: true,
            element: firstPagePath ? (
              <Navigate to={firstPagePath} replace />
            ) : (
              <div>暂无可访问页面</div>
            ),
          },
          ...createPageRoutes(PAGE_ROUTES),
        ],
      },
      { path: '/login', element: <Login /> },
      { path: '/logout', element: <Logout /> },
      { index: true, element: <Navigate to={PAGE_ROUTE} replace /> },
      { path: '*', element: <div>未找到页面</div> },
    ],
  },
];

const Routes = () => {
  return useRoutes(routeList);
};

export { Routes, routeList };
