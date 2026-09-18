import type { ReactNode } from 'react';

/** Every page under the main Layout is discovered from its stable pageId. */
export interface PageRouteDefinition {
  name: string;
  pageId: string;
  resourceKey?: string;
  hideInMenu?: boolean;
  icon?: ReactNode;
}
