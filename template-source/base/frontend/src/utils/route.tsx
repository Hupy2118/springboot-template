import type { Route } from '@typings/workbench';
import type { PageRouteDefinition } from '@/typings/routes';
import { pageRouteSegmentFromId } from '@/utils/pageIdentity';
const trim = (path?: string) => path?.replace(/^\/+|\/+$/g, '') ?? '';
const internal = (root: string, segments: string[]) =>
  `/${[trim(root), ...segments].join('/')}`;
export function createLayoutMenus(
  items: PageRouteDefinition[],
  root: string,
): Route[] {
  return items.map((item) => ({
    ...item,
    key: item.pageId ?? item.key,
    path: item.isUrl
      ? item.path
      : internal(root, [
          item.pageId ? pageRouteSegmentFromId(item.pageId) : trim(item.path),
        ]),
    children: item.children
      ? createLayoutMenus(item.children, root)
      : undefined,
  }));
}
export function findFirstPagePath(
  items: PageRouteDefinition[],
  root: string,
): string | undefined {
  for (const item of items) {
    if (item.pageId || item.modulePath)
      return internal(root, [
        item.pageId ? pageRouteSegmentFromId(item.pageId) : trim(item.path),
      ]);
    const child = item.children && findFirstPagePath(item.children, root);
    if (child) return child;
  }
}
