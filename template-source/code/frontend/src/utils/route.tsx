import type { Route } from '@/typings/workbench';
import type { ResolvedPageRoute } from '@/utils/pageRouteTree';

export type AccessibleNavigation = {
  menuRoutes: Route[];
  firstAccessiblePath?: string;
};

function isPermitted(
  item: ResolvedPageRoute,
  hasPermission: (resourceKey: string) => boolean,
) {
  return item.requiredResourceKeys.every(hasPermission);
}

/**
 * 在同一次授权遍历中生成可见菜单及 /page 默认入口。
 * 目录资源键已在标准化树中继承到全部后代，因此无权限目录会拦截整个子树。
 */
export function createAccessibleNavigation(
  hasPermission: (resourceKey: string) => boolean,
  items: ResolvedPageRoute[],
): AccessibleNavigation {
  let firstAccessiblePath: string | undefined;

  const walk = (nodes: ResolvedPageRoute[]): Route[] => nodes.flatMap<Route>((item): Route[] => {
    if (!item.visible || !isPermitted(item, hasPermission)) return [];

    if (item.kind === 'directory') {
      const children = walk(item.children);
      if (children.length === 0) return [];
      return [{
        key: item.menuKey,
        name: item.definition.label,
        icon: item.definition.icon,
        path: undefined,
        children,
      }];
    }

    if (item.kind === 'external') {
      return [{
        key: item.menuKey,
        name: item.definition.label,
        icon: item.definition.icon,
        path: item.definition.path,
        isUrl: true,
        target: item.definition.target,
        children: undefined,
      }];
    }

    if (!firstAccessiblePath) firstAccessiblePath = item.internalPath;
    return [{
      key: item.menuKey,
      name: item.definition.label,
      icon: item.definition.icon,
      path: item.internalPath,
      children: undefined,
    }];
  });

  return { menuRoutes: walk(items), firstAccessiblePath };
}

/** @deprecated Use createAccessibleNavigation. */
export const createAuthorizedNavigation = createAccessibleNavigation;
