import type { Route } from '@/typings/workbench';

/**
 * 业务导航的唯一配置模型。
 * 继承 ProLayout 菜单字段，业务页面由 pageId 确定页面目录与路由地址。
 */
export type PageRouteDefinition = Omit<Route, 'children' | 'key'> & {
  children?: PageRouteDefinition[];
  /** XcodeAgent 业务页标识；用于确定页面目录和路由地址。 */
  pageId?: string;
  /** 平台系统页的显式模块目录，不适用 pageId 业务页转换规则。 */
  modulePath?: string;
  /** 预留给授权分支的页面或外链资源绑定。 */
  resourceKey?: string;
};
