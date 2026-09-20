import type { PageRouteDefinition } from '@/typings/routes';
import { RESOURCES } from '@/constants/resources';
import { resolvePageRouteTree } from '@/utils/pageRouteTree';

/** 业务路由根路径；业务页面 URL 始终以 /page 开头。 */
export const PAGE_ROUTE = 'page';

/**
 * 页面路由、路由权限与菜单入口的唯一配置来源。
 * 后续业务页面请在 XCODEAGENT_BUSINESS_ROUTES 标记之间追加配置。
 * 仅当平台 authorization_manifest 明确绑定页面资源时，才添加 resourceKey。
 * 未声明 resourceKey 的页面不参与前端业务权限控制，也不得自动补充资源点。
 */
export const PAGE_ROUTES: PageRouteDefinition[] = [
  {
    path: 'home',
    label: '首页',
  },
  // @xcodeagent-extension authorization
  {
    path: 'authorization_management',
    label: '权限管理',
    icon: 'SafetyCertificateOutlined',
    resourceKey: RESOURCES.SYSTEM.AUTHORIZATION_MANAGEMENT,
  },
  // XCODEAGENT_BUSINESS_ROUTES_START
  // XCODEAGENT_BUSINESS_ROUTES_END
];

/** 由唯一页面配置构建一次，供路由、菜单与默认入口共享。 */
export const PAGE_ROUTE_TREE = resolvePageRouteTree(PAGE_ROUTES, PAGE_ROUTE);
