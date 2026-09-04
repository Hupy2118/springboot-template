import type { Route } from '@/typings/workbench';

/** 由引擎生成的菜单变换链；无扩展时保持恒等。 */
export const useCapabilityMenus = (menus: Route[]): Route[] => menus;
