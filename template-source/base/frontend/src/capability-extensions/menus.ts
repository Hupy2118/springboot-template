import type { Route } from '@/typings/workbench';

/*
 * CAPABILITY EXTENSION SURFACE
 *
 * 此文件允许 Capability Authoring 修改。
 * 允许新增 import，以及在 xcodeagent Anchor 前新增内容。
 * 禁止删除或修改 Anchor，或修改 Anchor 之外的 Base 内容。
 */

/** 用于 Authorization 等需要统一改变菜单行为的 Capability。 */
export const useCapabilityMenus = (menus: Route[]): Route[] => {
  let current = menus;
  // xcodeagent:capability-menu-transforms
  return current;
};
