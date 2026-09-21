import { useCallback } from 'react';
import { useAccess } from '@/platform/access/useAccess';

export function usePermission() {
  const { state, hasPermission, hasAllPermissions } = useAccess();
  const hasAnyPermission = useCallback((resourceKeys: readonly string[]) => resourceKeys.some(hasPermission), [hasPermission]);
  return {
    hasPermission: (resourceKey: string) => state === 'ready' && hasPermission(resourceKey),
    hasAnyPermission: (resourceKeys: readonly string[]) => state === 'ready' && hasAnyPermission(resourceKeys),
    hasAllPermissions: (resourceKeys: readonly string[]) => state === 'ready' && hasAllPermissions(resourceKeys),
  };
}
