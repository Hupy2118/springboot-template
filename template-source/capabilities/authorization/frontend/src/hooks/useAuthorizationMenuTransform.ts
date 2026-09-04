import { useMemo } from 'react';
import { usePermission } from '@/hooks/usePermission';
import type { Route } from '@/typings/workbench';

type ProtectedRoute = Route & { resourceKey?: string; children?: ProtectedRoute[] };

function filterMenus(items: ProtectedRoute[], hasPermission: (resourceKey: string) => boolean): ProtectedRoute[] {
  return items.flatMap((item) => {
    if (item.resourceKey && !hasPermission(item.resourceKey)) return [];
    const children = item.children ? filterMenus(item.children, hasPermission) : undefined;
    return [{ ...item, ...(children ? { children } : {}) }];
  });
}

export function useAuthorizationMenuTransform(menus: Route[]): Route[] {
  const { hasPermission } = usePermission();
  return useMemo(() => filterMenus(menus as ProtectedRoute[], hasPermission), [menus, hasPermission]);
}
