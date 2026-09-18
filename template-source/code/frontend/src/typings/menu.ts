import type { ReactNode } from 'react';

/** Route 的菜单投影；菜单不反向定义页面路由。 */
export interface AppMenuItem {
  key: string;
  name: string;
  path?: string;
  icon?: ReactNode;
  hideInMenu?: boolean;
  disabled?: boolean;
  target?: string;
  isUrl?: boolean;
  /** Capability menu transforms may use the page's access key. */
  resourceKey?: string;
  children?: AppMenuItem[];
}
