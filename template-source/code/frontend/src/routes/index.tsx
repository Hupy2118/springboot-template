import { Navigate, RouteObject, useRoutes } from 'react-router-dom';
import Layout from '@/layout';
import Login from '@/pages/Login';
import Logout from '@/pages/Logout';
import { AuthStateView } from '@/components/Authorization/RouteGuard';
import { PAGE_ROUTE, PAGE_ROUTE_TREE } from '@/constants/routes';
import { usePageMenus } from '@/hooks/usePageMenus';
import { createProtectedRoutes } from '@/utils/protectedRoutes';

function PageEntryRedirect() {
  const { state, firstAccessiblePath } = usePageMenus();

  if (firstAccessiblePath) return <Navigate to={firstAccessiblePath} replace />;
  if (state !== 'ready') return <AuthStateView state={state} />;

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
          ...createProtectedRoutes(PAGE_ROUTE_TREE),
        ],
      },
      // @xcodeagent-extension login
      { path: '/login', element: <Login /> },
      { path: '/logout', element: <Logout /> },
      { index: true, element: <Navigate to='/login' replace /> },
      { path: '*', element: <div>未找到页面</div> },
    ],
  },
];

const Routes = () => {
  return useRoutes(routeList);
};

export { Routes, routeList };
