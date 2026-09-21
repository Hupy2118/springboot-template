import { Navigate, RouteObject, useRoutes } from 'react-router-dom';
import Layout from '@/layout';
import { AccessStateView } from '@/platform/access/AccessStateView';
import { PAGE_ROUTE } from '@/constants/routes';
import { usePageMenus } from '@/hooks/usePageMenus';
import { createAccessibleRoutes } from './routeBuilder';
import { APP_PAGE_ROUTE_TREE } from './pageRegistry';
import { rootRoutes } from './rootRoutes';

function PageEntryRedirect() {
  const { state, firstAccessiblePath } = usePageMenus();

  if (firstAccessiblePath) return <Navigate to={firstAccessiblePath} replace />;
  if (state !== 'ready') return <AccessStateView state={state} />;

  return <div className='authorization-state'>暂无可访问页面</div>;
}

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
          { index: true, element: <PageEntryRedirect /> },
          ...createAccessibleRoutes(APP_PAGE_ROUTE_TREE),
        ],
      },
      ...rootRoutes,
      { index: true, element: <Navigate to={`/${PAGE_ROUTE}`} replace /> },
      { path: '*', element: <div>未找到页面</div> },
    ],
  },
];

const Routes = () => {
  return useRoutes(routeList);
};

export { Routes, routeList };
