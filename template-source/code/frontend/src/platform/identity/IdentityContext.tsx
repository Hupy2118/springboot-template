import { createContext, useMemo, useState, type PropsWithChildren } from 'react';

export interface Identity {
  userId: string;
  userName: string;
  avatar?: string;
}

export interface IdentityContextValue {
  identity: Identity | null;
  setIdentity: (identity: Identity | null) => void;
}

export const IdentityContext = createContext<IdentityContextValue>({
  identity: null,
  setIdentity: () => undefined,
});

export function IdentityProvider({ children }: PropsWithChildren) {
  const [identity, setIdentity] = useState<Identity | null>(null);
  const value = useMemo(() => ({ identity, setIdentity }), [identity]);
  return <IdentityContext.Provider value={value}>{children}</IdentityContext.Provider>;
}
