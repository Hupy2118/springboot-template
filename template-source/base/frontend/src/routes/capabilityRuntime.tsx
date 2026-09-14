import type { ReactNode } from 'react';
import type { PageRouteDefinition } from '@/typings/routes';
import { capabilityPageWrappers, capabilityRootRoutes } from '@/capability-extensions/routes';

/** Base internal: this file is not a Capability extension surface. */
export const capabilityEntryPath: string | undefined =
  capabilityRootRoutes.find((route) => route.handle?.capabilityEntry)?.path;

/** Base internal: applies the extension list in its registered order. */
export const wrapCapabilityPage = (element: ReactNode, page: PageRouteDefinition): ReactNode =>
  capabilityPageWrappers.reduceRight((current, wrap) => wrap(current, page), element);
