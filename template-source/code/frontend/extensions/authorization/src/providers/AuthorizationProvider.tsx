import { useCallback, useMemo, type PropsWithChildren } from 'react';
import { useRequest } from 'ahooks';
import { AuthorizationApiError, getMyResources } from '@/apis/authorization';
import { AccessContext, type AccessState } from '@/platform/access/AccessContext';

const statusOf = (error: unknown) => (error as { response?: { status?: number } })?.response?.status;

export function AuthorizationProvider({ children }: PropsWithChildren) {
  const { data, loading, error, refreshAsync } = useRequest(getMyResources);
  const permissions = useMemo(() => new Set(data?.resourceKeys ?? []), [data]);
  const returnCode = error instanceof AuthorizationApiError ? error.returnCode : undefined;
  const state: AccessState = loading
    ? 'loading'
    : error
      ? statusOf(error) === 401 || returnCode === 'XCD1B11'
        ? 'unauthenticated'
        : statusOf(error) === 503 || returnCode === 'XCD1B12'
          ? 'not-ready'
          : 'error'
      : 'ready';
  const hasPermission = useCallback((resourceKey: string) => permissions.has(resourceKey), [permissions]);
  const hasAllPermissions = useCallback((resourceKeys: readonly string[]) => resourceKeys.every(hasPermission), [hasPermission]);
  const value = useMemo(() => ({ state, hasPermission, hasAllPermissions, refresh: refreshAsync }), [hasAllPermissions, hasPermission, refreshAsync, state]);
  return <AccessContext.Provider value={value}>{children}</AccessContext.Provider>;
}
