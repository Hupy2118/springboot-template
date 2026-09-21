import { useMemo } from 'react';
import { APP_PAGE_ROUTE_TREE } from '@/routes/pageRegistry';
import { useAccess } from '@/platform/access/useAccess';
import { createAccessibleNavigation } from '@/utils/route';

/** 为布局和首页提供当前权限下可见的页面菜单。 */
export function usePageMenus() {
  const { state, hasPermission } = useAccess();
  const navigation = useMemo(
    () => createAccessibleNavigation(hasPermission, APP_PAGE_ROUTE_TREE),
    [hasPermission],
  );

  return { state, ...navigation };
}
