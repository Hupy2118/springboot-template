import type { AppMenuItem } from '@/typings/menu';

/*
 * CAPABILITY EXTENSION SURFACE
 *
 * Capability calls remain statically injected Hook calls. Do not replace this
 * sequence with map/reduce because Hook call order must be stable.
 */
export const useCapabilityMenuTransforms = (menus: AppMenuItem[]): AppMenuItem[] => {
  let current = menus;
  // xcodeagent:capability-menu-transforms
  return current;
};
