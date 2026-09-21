import type { PropsWithChildren } from 'react';
import { IdentityProvider } from '@/platform/identity/IdentityContext';
import { LoginProvider as Provider0 } from '@/providers/LoginProvider';
import { AuthorizationProvider as Provider1 } from '@/providers/AuthorizationProvider';

export function AppProviders({ children }: PropsWithChildren) {
  return <IdentityProvider><Provider0><Provider1>{children}</Provider1></Provider0></IdentityProvider>;
}
