import { useMemo } from 'react';
import { PAGE_ROUTE_TREE } from '@/constants/routes';
import { useAuth } from '@/providers/AuthProvider';
import { usePermission } from '@/hooks/usePermission';
import { createAuthorizedNavigation } from '@/utils/route';

/** 为布局和首页提供当前权限下可见的页面菜单。 */
export function usePageMenus() {
  const { state } = useAuth();
  const { hasPermission } = usePermission();
  const navigation = useMemo(
    () => createAuthorizedNavigation(hasPermission, PAGE_ROUTE_TREE),
    [hasPermission],
  );

  return { state, ...navigation };
}
