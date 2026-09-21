import { useState, type PropsWithChildren } from 'react';
import type { LoginInfo } from '@/typings/login';
import { LoginContext } from '@/context/LoginContext';
import { useGuard } from '@/hooks/useGuard';

function LoginGuard({ children }: PropsWithChildren) {
  useGuard();
  return <>{children}</>;
}

export function LoginProvider({ children }: PropsWithChildren) {
  const [authInfo, setAuthInfo] = useState<LoginInfo | null>(null);
  return (
    <LoginContext.Provider value={{ authInfo, setAuthInfo }}>
      <LoginGuard>{children}</LoginGuard>
    </LoginContext.Provider>
  );
}
