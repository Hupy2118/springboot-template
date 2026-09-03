import { cloneElement, type ReactElement } from 'react';
import { usePermission } from '@/hooks/usePermission';

export type PermissionMode = 'hidden' | 'disabled';

type DisableableProps = {
  disabled?: boolean;
  'aria-disabled'?: boolean;
};

type PermissionProps = {
  resourceKey: string;
  mode?: PermissionMode;
  children: ReactElement<DisableableProps>;
};

/** 统一控制具备 disabled 属性的操作控件。 */
export function Permission({ resourceKey, mode = 'hidden', children }: PermissionProps) {
  const { hasPermission } = usePermission();
  const permitted = hasPermission(resourceKey);

  if (permitted) return children;
  if (mode === 'hidden') return null;

  return cloneElement(children, {
    disabled: true,
    'aria-disabled': true,
  });
}
