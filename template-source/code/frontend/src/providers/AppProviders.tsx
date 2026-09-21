import type { PropsWithChildren } from 'react';
import { IdentityProvider } from '@/platform/identity/IdentityContext';
import { LoginProvider } from './LoginProvider';
import { AuthorizationProvider } from './AuthorizationProvider';

/** 应用级 Provider 的静态装配；外层 Provider 先提供其依赖的上下文。 */
export function AppProviders({ children }: PropsWithChildren) {
  return (
    <IdentityProvider>
      <LoginProvider>
        <AuthorizationProvider>{children}</AuthorizationProvider>
      </LoginProvider>
    </IdentityProvider>
  );
}
