import type { Route } from '@/typings/workbench';

/** 稳定扩展面；调用按 Registry 顺序静态插入。 */
export const useCapabilityMenus = (menus: Route[]): Route[] => {
  let current = menus;
  // xcodeagent:capability-menu-transforms
  return current;
};
