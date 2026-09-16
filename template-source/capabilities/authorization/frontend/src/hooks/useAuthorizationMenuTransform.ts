import { useMemo } from 'react';
import { usePermission } from '@/hooks/usePermission';
import type { AppMenuItem } from '@/typings/menu';

function filterMenus(items: AppMenuItem[], hasPermission: (resourceKey: string) => boolean): AppMenuItem[] {
  return items.flatMap((item) => {
    if (item.resourceKey && !hasPermission(item.resourceKey)) return [];
    const children = item.children ? filterMenus(item.children, hasPermission) : undefined;
    return [{ ...item, ...(children ? { children } : {}) }];
  });
}

export function useAuthorizationMenuTransform(menus: AppMenuItem[]): AppMenuItem[] {
  const { hasPermission } = usePermission();
  return useMemo(() => filterMenus(menus, hasPermission), [menus, hasPermission]);
}
