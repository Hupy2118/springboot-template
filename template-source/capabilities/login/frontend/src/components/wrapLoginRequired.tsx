import { useContext, type PropsWithChildren, type ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { GlobalContext } from '@/providers';
import type { PageRouteDefinition } from '@/typings/routes';

/** Login capability's page-level boundary for both Base and capability pages. */
export function wrapLoginRequired(element: ReactNode, _page: PageRouteDefinition): ReactNode {
  return <LoginRequired>{element}</LoginRequired>;
}

/** Hook 必须在组件渲染期执行，不能在路由定义构造期执行。 */
function LoginRequired({ children }: PropsWithChildren) {
  const { userInfo } = useContext(GlobalContext);
  const location = useLocation();
  if (userInfo) return <>{children}</>;
  return <Navigate to='/login' replace state={{ from: location.pathname }} />;
}
