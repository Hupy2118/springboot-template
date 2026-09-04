import type { ComponentType } from 'react';
import type { Route } from '@/typings/workbench';

export type PageRouteDefinition = Omit<Route, 'children' | 'key'> & {
  children?: PageRouteDefinition[];
  pageId?: string;
  modulePath?: string;
  component?: ComponentType;
  resourceKey?: string;
};
