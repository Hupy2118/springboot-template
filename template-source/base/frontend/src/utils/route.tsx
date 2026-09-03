import type { Route } from '@typings/workbench';
import type { PageRouteDefinition } from '@/typings/routes';
import { pageRouteSegmentFromId } from '@/utils/pageIdentity';

function normalizePathSegment(path?: string) {
  return path?.replace(/^\/+|\/+$/g, '') ?? '';
}

function toInternalPath(rootPath: string, pathSegments: string[]) {
  return `/${[normalizePathSegment(rootPath), ...pathSegments].join('/')}`;
}

/** 从统一业务配置生成 ProLayout 菜单，并将内部链接解析为 /page/... 绝对路径。 */
export function createLayoutMenus(items: PageRouteDefinition[], rootPath: string): Route[] {
  const normalizedRootPath = normalizePathSegment(rootPath);

  const walk = (nodes: PageRouteDefinition[], parentPathSegments: string[]): Route[] => nodes.map((item) => {
    const normalizedPath = item.pageId
      ? pageRouteSegmentFromId(item.pageId)
      : normalizePathSegment(item.path);
    const nextParentPathSegments = normalizedPath && !item.isUrl && !item.pageId
      ? [...parentPathSegments, normalizedPath]
      : parentPathSegments;
    const children = item.children?.length ? walk(item.children, nextParentPathSegments) : undefined;
    const nextItem: Route = {
      ...item,
      key: item.pageId ?? item.key,
      path: item.isUrl || !normalizedPath
        ? item.path
        : item.pageId
          ? toInternalPath(normalizedRootPath, [normalizedPath])
          : toInternalPath(normalizedRootPath, nextParentPathSegments),
      children,
    };
    delete (nextItem as PageRouteDefinition).pageId;
    delete (nextItem as PageRouteDefinition).modulePath;
    delete (nextItem as PageRouteDefinition).resourceKey;
    return nextItem;
  });

  return Array.isArray(items) ? walk(items, []) : [];
}

/** 返回第一个可导航的业务页面，用于 /page 入口重定向。 */
export function findFirstPagePath(items: PageRouteDefinition[], rootPath: string): string | undefined {
  const normalizedRootPath = normalizePathSegment(rootPath);
  const find = (nodes: PageRouteDefinition[], parentPathSegments: string[]): string | undefined => {
    for (const node of nodes) {
      const normalizedPath = node.pageId
        ? pageRouteSegmentFromId(node.pageId)
        : normalizePathSegment(node.path);
      const nextParentPathSegments = normalizedPath && !node.isUrl && !node.pageId
        ? [...parentPathSegments, normalizedPath]
        : parentPathSegments;
      if ((node.pageId || node.modulePath) && normalizedPath && !node.isUrl) {
        return node.pageId
          ? toInternalPath(normalizedRootPath, [normalizedPath])
          : toInternalPath(normalizedRootPath, nextParentPathSegments);
      }
      const childPath = node.children ? find(node.children, nextParentPathSegments) : undefined;
      if (childPath) return childPath;
    }
    return undefined;
  };
  return find(items, []);
}
