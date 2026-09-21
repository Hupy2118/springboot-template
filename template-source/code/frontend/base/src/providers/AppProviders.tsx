import type { PropsWithChildren } from 'react';
import { IdentityProvider } from '@/platform/identity/IdentityContext';
import { extensionProviders } from '@/generated/extensions/providers';

export function AppProviders({ children }: PropsWithChildren) {
  const content = extensionProviders.reduceRight(
    (current, Provider) => <Provider>{current}</Provider>,
    children,
  );

  return <IdentityProvider>{content}</IdentityProvider>;
}
