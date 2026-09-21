import type { RouteObject } from 'react-router-dom';
import Login from '@/pages/Login';
import Logout from '@/pages/Logout';

/** 不属于 /page 页面树的应用根路由。 */
export const rootRoutes: RouteObject[] = [
  { path: '/login', element: <Login /> },
  { path: '/logout', element: <Logout /> },
];
