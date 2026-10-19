import { createContext } from 'react';

export type AccessState = 'ready' | 'loading' | 'unauthenticated' | 'not-ready' | 'error';

export interface AccessContextValue {
  state: AccessState;
  hasPermission: (resourceKey: string) => boolean;
  hasAllPermissions: (resourceKeys: readonly string[]) => boolean;
  refresh?: () => Promise<unknown>;
}

export const defaultAccess: AccessContextValue = {
  state: 'ready',
  hasPermission: () => true,
  hasAllPermissions: () => true,
};

export const AccessContext = createContext<AccessContextValue>(defaultAccess);
