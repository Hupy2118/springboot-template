import { useCallback } from 'react';
import { useAuth } from '@/providers/AuthProvider';

type ResourceKeys = readonly string[];

/** 提供业务层的权限判断能力。 */
export function usePermission() {
  const { permissions, state } = useAuth();

  const hasPermission = useCallback(
    (resourceKey: string) => state === 'ready' && permissions.has(resourceKey),
    [permissions, state],
  );
  const hasAnyPermission = useCallback(
    (resourceKeys: ResourceKeys) => resourceKeys.some(hasPermission),
    [hasPermission],
  );
  const hasAllPermissions = useCallback(
    (resourceKeys: ResourceKeys) => resourceKeys.length > 0 && resourceKeys.every(hasPermission),
    [hasPermission],
  );

  return { hasPermission, hasAnyPermission, hasAllPermissions };
}
