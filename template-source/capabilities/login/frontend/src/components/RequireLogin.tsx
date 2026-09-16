import { useContext } from 'react';
import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { GlobalContext } from '@/providers';

/** Login capability's application-level Route Guard. */
export function RequireLogin() {
  const { userInfo } = useContext(GlobalContext);
  const location = useLocation();
  if (userInfo) return <Outlet />;
  return <Navigate to='/login' replace state={{ from: location.pathname }} />;
}
