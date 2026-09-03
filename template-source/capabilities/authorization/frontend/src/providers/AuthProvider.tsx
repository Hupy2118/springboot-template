import { createContext, PropsWithChildren, useContext, useMemo } from 'react';
import { useRequest } from 'ahooks';
import { AuthorizationApiError, getMyResources } from '@/apis/authorization';

export type AuthState = 'loading' | 'ready' | 'unauthenticated' | 'forbidden' | 'not-ready' | 'error';
type AuthContextValue = {
  permissions: ReadonlySet<string>;
  state: AuthState;
  loading: boolean;
  error?: unknown;
  refreshPermissions: () => Promise<unknown>;
};

const AuthContext = createContext<AuthContextValue>({
  permissions: new Set(), state: 'loading', loading: true, refreshPermissions: async () => undefined,
});
const statusOf = (error: unknown) => (error as { response?: { status?: number } })?.response?.status;

export function AuthProvider({ children }: PropsWithChildren) {
  const { data, loading, error, refreshAsync } = useRequest(getMyResources);
  const resourceKeys = data?.resourceKeys ?? [];
  const permissions = useMemo(() => new Set(resourceKeys), [resourceKeys]);
  const returnCode = error instanceof AuthorizationApiError ? error.returnCode : undefined;
  const state: AuthState = loading ? 'loading' : error
    ? statusOf(error) === 401 || returnCode === 'XCD1B11' ? 'unauthenticated' : statusOf(error) === 403 || returnCode === 'XCD1B10' ? 'forbidden' : statusOf(error) === 503 || returnCode === 'XCD1B12' ? 'not-ready' : 'error'
    : 'ready';
  const value = useMemo(() => ({
    permissions, state, loading, error,
    refreshPermissions: refreshAsync,
  }), [error, loading, permissions, refreshAsync, state]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export const useAuth = () => useContext(AuthContext);
