import type { RouteObject } from 'react-router-dom';
import RootRoute0 from '@/pages/Login/index';
import RootRoute1 from '@/pages/Logout/index';

export const rootRoutes: RouteObject[] = [
  { path: '/login', element: <RootRoute0 /> },
  { path: '/logout', element: <RootRoute1 /> },
];
