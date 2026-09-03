import type { Route } from '@/typings/workbench';
export type PageRouteDefinition = Omit<Route, 'children' | 'key'> & { children?: PageRouteDefinition[]; pageId?: string; modulePath?: string; };
