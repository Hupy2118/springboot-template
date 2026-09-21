import { cloneElement, type ReactElement } from 'react';
import { usePermission } from '@/hooks/usePermission';

export type PermissionMode = 'hidden' | 'disabled';
type DisableableProps = { disabled?: boolean; 'aria-disabled'?: boolean };

export function Permission({ resourceKey, mode = 'hidden', children }: { resourceKey: string; mode?: PermissionMode; children: ReactElement<DisableableProps> }) {
  const { hasPermission } = usePermission();
  if (hasPermission(resourceKey)) return children;
  if (mode === 'hidden') return null;
  return cloneElement(children, { disabled: true, 'aria-disabled': true });
}
