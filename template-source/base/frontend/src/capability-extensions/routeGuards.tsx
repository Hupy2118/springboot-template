import type { ReactNode } from 'react';
import type { PageRouteDefinition } from '@/typings/routes';

export interface RouteGuardDefinition {
  element: ReactNode;
}

export type AppRouteGuardFactory = () => RouteGuardDefinition | undefined;

export type PageRouteGuardFactory = (
  page: PageRouteDefinition,
) => RouteGuardDefinition | undefined;

/*
 * CAPABILITY EXTENSION SURFACE
 *
 * Guard registration order is outer to inner. Route Builder applies the
 * factories with reduceRight so generated nesting stays deterministic.
 */
export const appRouteGuards: AppRouteGuardFactory[] = [
  // devagentstudio:app-route-guards
];

export const pageRouteGuards: PageRouteGuardFactory[] = [
  // devagentstudio:page-route-guards
];
