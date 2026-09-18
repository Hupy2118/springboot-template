import type { AppMenuItem } from '@/typings/menu';
import type { PageRouteDefinition } from '@/typings/routes';
import { pageRouteSegmentFromId } from '@/utils/pageIdentity';
const trim = (path: string) => path.replace(/^\/+|\/+$/g, '');
const internal = (root: string, segments: string[]) =>
  `/${[trim(root), ...segments].join('/')}`;

/** Resolves the sole Route path convention for pages under the main Layout. */
export function resolvePagePath(page: PageRouteDefinition, root?: string): string {
  const segment = pageRouteSegmentFromId(page.pageId);
  return root === undefined ? segment : internal(root, [segment]);
}

export function createLayoutMenus(
  items: PageRouteDefinition[],
  root: string,
): AppMenuItem[] {
  return items.map((item) => ({
    key: item.pageId,
    name: item.name,
    icon: item.icon,
    hideInMenu: item.hideInMenu,
    resourceKey: item.resourceKey,
    path: resolvePagePath(item, root),
  }));
}
export function findFirstPagePath(
  items: PageRouteDefinition[],
  root: string,
): string | undefined {
  for (const item of items) {
    return resolvePagePath(item, root);
  }
}
