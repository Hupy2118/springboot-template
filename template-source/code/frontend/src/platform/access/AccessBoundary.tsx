import type { PropsWithChildren } from 'react';
import { AccessStateView } from './AccessStateView';
import { useAccess } from './useAccess';

export function AccessBoundary({ resourceKeys, children }: PropsWithChildren<{ resourceKeys: readonly string[] }>) {
  const access = useAccess();
  if (resourceKeys.length === 0) return <>{children}</>;
  if (access.state !== 'ready') return <AccessStateView state={access.state} />;
  return access.hasAllPermissions(resourceKeys) ? <>{children}</> : <AccessStateView state='forbidden' />;
}
