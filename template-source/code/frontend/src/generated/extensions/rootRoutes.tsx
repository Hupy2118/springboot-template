import type { RouteObject } from 'react-router-dom';
import LoginPage from '@/pages/Login/index';
import LogoutPage from '@/pages/Logout/index';

export const extensionRootRoutes: RouteObject[] = [
  { path: '/login', element: <LoginPage /> },
  { path: '/logout', element: <LogoutPage /> },
];
